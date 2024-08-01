import Instances.{*, given}
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import cats.syntax.all.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax._
import io.circe.yaml
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.DispatcherSelector

import java.util.concurrent.Semaphore

case class Case(
    val nMsg: Int,
    val nWin: Int,
    val nActor: Int
)

case class Config(
    val cases: List[Case],
    val output: String
)

case class CaseResult(
    val nMsg: Int,
    val nWin: Int,
    val nActor: Int,
    val msgPerSec: Double,
    val time: Double
)

val runCase: Case => CaseResult = cfg =>
  val nMsg = cfg.nMsg
  val nWin = cfg.nWin
  val nActor = cfg.nActor

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
            _ <- nextWindow[GCounter[Double, ProcId], Int, IntRange]
            _ <- await[GCounter[Double, ProcId], Int, IntRange](
              msg / (nMsg / nWin) - 1
            )
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
    nMsg = nMsg,
    nWin = nWin,
    nActor = nActor,
    time = t,
    msgPerSec = avg
  )

@main def main =
  val cfg: Config = yaml.parser
  .parse(scala.io.Source.fromResource("cfg.yaml").mkString)
  .leftMap(err => err: Error)
  .flatMap(_.as[Config])
  .valueOr(throw _)

  val results = Json fromValues cfg.cases.map(x => runCase(x).asJson)

  val fw = java.io.FileWriter(cfg.output, false)
  fw.write(results.toString)
  fw.flush()
  fw.close()
