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

import java.util.concurrent.Semaphore

case class Case(
    val nTotalMsg: Int,
    val nWin: Int,
    val nActor: Int,
    val nWinPerAwait: Int
)

case class Grid(
    val nTotalMsg: List[Int],
    val nWin: List[Int],
    val nActor: List[Int],
    val nWinPerAwait: List[Int]
)
case class Config(
    val grids: List[Grid],
    val output: String
)

case class CaseResult(
    val nTotalMsg: Int,
    val nWin: Int,
    val nActor: Int,
    val nWinPerAwait: Int,
    val msgPerSec: Double,
    val time: Double
)

val runCase: Case => CaseResult = cfg =>
  val nMsg = cfg.nTotalMsg / cfg.nActor
  val nWin = cfg.nWin
  val nActor = cfg.nActor
  val nWinPerAwait = cfg.nWinPerAwait

  val semaphore: Semaphore = new Semaphore(-nActor + 1)

  val handle: HandleM[GCounter[Double, ProcId], Int, IntRange, Unit] =
    for {
      msg <- getMsg
      procId <- getProcId
      _ <- modifyCRDT[GCounter[Double, ProcId], Int, IntRange](gs =>
        gs.increase(procId)(msg)
      )
      _ <-
        if nWin != 0 && msg % (nMsg / nWin) == 0 && msg != 0 then
          for {
            win <- pure(nMsg / nWin)
            _ <- nextWindow[GCounter[Double, ProcId], Int, IntRange]
            _ <-
              if win % nWinPerAwait == 0 then
                await[GCounter[Double, ProcId], Int, IntRange](
                  msg / (nMsg / nWin) - 1
                ) >> pure(())
              else pure(())
          } yield ()
        else pure(())
      _ <-
        if msg == nMsg then
          liftIO[GCounter[Double, ProcId], Int, IntRange, Unit] {
            semaphore.release()
          }
        else pure(())
    } yield ()

  val stream = IntRange(0, nMsg + 1)

  val start = System.currentTimeMillis()

  val _ = ActorSystem(
    ActorMain.withDispatcher[GCounter[Double, ProcId], Int, IntRange](
      GCounter.newGCounter[Double, ProcId]
    )(
      List.fill(nActor)(handle -> stream)
    )(DispatcherSelector.fromConfig("benchmark-dispatcher")),
    "TestSystem"
  )

  semaphore.acquire()

  val end = System.currentTimeMillis()

  val t = (end * 1.0 - start * 1.0) / 1000
  val avg = nMsg * nActor / t

  CaseResult(
    nTotalMsg = nMsg * nActor,
    nWin = nWin,
    nActor = nActor,
    nWinPerAwait = nWinPerAwait,
    time = t,
    msgPerSec = avg
  )

def main(args: String*) =
  val cfgStr =
    if args.length >= 1 then scala.io.Source.fromFile(args.head).mkString
    else scala.io.Source.fromResource("cfg.yaml").mkString
  val cfg: Config = yaml.parser
    .parse(cfgStr)
    .leftMap(err => err: Error)
    .flatMap(_.as[Config])
    .valueOr(throw _)

  val girdToCases = (grid: Grid) =>
    for {
      nTotalMsg <- grid.nTotalMsg
      nActor <- grid.nActor
      nWin <- grid.nWin
      nWinPerAwait <- grid.nWinPerAwait
    } yield Case(
      nTotalMsg = nTotalMsg,
      nActor = nActor,
      nWin = nWin,
      nWinPerAwait = nWinPerAwait
    )

  val cases = List.fill(5)((cfg.grids map girdToCases).flatten).flatten

  val fw = java.io.FileWriter(cfg.output, false)
  cases.foreach(x => {
    val result = runCase(x).asJson
    fw.write(result.toString.replaceAll("\\s+", "").appended('\n'))
    fw.flush()
  })

  fw.close()
