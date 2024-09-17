package wcrdt

import Instances.{*, given}
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import cats.syntax.all.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.circe.yaml
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.DispatcherSelector

import java.util.concurrent.atomic.AtomicInteger

case class Case(
    val nMsgPerWin: Int,
    val nActor: Int,
    val nWinPerAwait: Int,
    val time: Int,
    val delay: Int,
    val warmup: Boolean
)

case class Grid(
    val nMsgPerWin: List[Int],
    val nActor: List[Int],
    val nWinPerAwait: List[Int],
    val time: Int,
    val warmup: Boolean
)

case class Config(
    val grids: List[Grid],
    val output: String,
    val repeat: Int,
    val delay: Int
)

case class CaseResult(
    val nMsg: Int,
    val nMsgPerWin: Int,
    val nActor: Int,
    val nWinPerAwait: Int,
    val msgPerSec: Double,
    val time: Double
)

val runCase: Case => CaseResult = cfg =>
  val nActor = cfg.nActor
  val nMsgPerWin = cfg.nMsgPerWin
  val nWinPerAwait = cfg.nWinPerAwait

  val handle: HandleM[GCounter[Double, ProcId], Int, FlagIntStream, Unit] =
    for {
      msg <- getMsg
      procId <- getProcId
      _ <- modifyCRDT[GCounter[Double, ProcId], Int, FlagIntStream](gs =>
        gs.increase(procId)(msg)
      )
      _ <- liftIO(Thread.sleep(1))
      _ <-
        if msg != 0 && msg % nMsgPerWin == 0 then
          nextWindowDelayed[GCounter[Double, ProcId], Int, FlagIntStream](cfg.delay)
        else pure(())
      w <- currentWindow
      _ <-
        if w % nWinPerAwait == 0 && w > 0 then await(w - 1) >> pure(())
        else pure(())
    } yield ()

  val trackers = List.fill(nActor)(new AtomicInteger)
  val streams = trackers map (FlagIntStream.newFlagIntStream)
  val terminate =
    Thread(() => {
      Thread.sleep(cfg.time * 1000)
      streams.foreach(x => x.setFlag)
    })

  val start = System.currentTimeMillis()
  terminate.start()

  val _ = ActorSystem(
    ActorMain.withDispatcher[GCounter[Double, ProcId], Int, FlagIntStream](
      GCounter.newGCounter[Double, ProcId]
    )(
      streams.map(s => handle -> s)
    )(DispatcherSelector.fromConfig("benchmark-dispatcher")),
    "TestSystem"
  )

  terminate.join()
  val end = System.currentTimeMillis()
  val nMsg = trackers.map(x => x.get()).sum

  val t = (end * 1.0 - start * 1.0) / 1000
  val avg = nMsg / t

  CaseResult(
    nMsg = nMsg,
    nMsgPerWin = nMsgPerWin,
    nActor = nActor,
    nWinPerAwait = nWinPerAwait,
    time = t,
    msgPerSec = avg
  )

@main def mainScale(args: String*) =
  val cfgStr =
    if args.length >= 1 then scala.io.Source.fromFile(args.head).mkString
    else scala.io.Source.fromResource("scale_cfg.yaml").mkString
  val cfg: Config = yaml.parser
    .parse(cfgStr)
    .leftMap(err => err: Error)
    .flatMap(_.as[Config])
    .valueOr(throw _)

  val girdToCases = (delay: Int) => (grid: Grid) =>
    for {
      nActor <- grid.nActor
      nMsgPerWin <- grid.nMsgPerWin
      nWinPerAwait <- grid.nWinPerAwait
    } yield Case(
      nActor = nActor,
      nMsgPerWin = nMsgPerWin,
      nWinPerAwait = nWinPerAwait,
      time = grid.time,
      warmup = grid.warmup,
      delay = delay
    )

  val cases = List.fill(cfg.repeat)((cfg.grids map girdToCases(cfg.delay)).flatten).flatten

  val fw = java.io.FileWriter(cfg.output, false)
  cases.foreach(x => {
    val result = runCase(x).asJson
    if !x.warmup then
      fw.write(result.toString.replaceAll("\\s+", "").appended('\n'))
      fw.flush()
    else ()
  })

  fw.close()
