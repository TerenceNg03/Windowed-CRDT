import Instances.{*, given}
import MVar.newMVar
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.*
import org.scalatest.tagobjects.Slow

import flatspec.*
import matchers.*

class ActorPressureTest extends AnyFlatSpec with should.Matchers:
  it should "handle 5* 2million stream" taggedAs (Slow) in:
    val mvar: MVar[Double] = newMVar
    val nMsg = 2_000_000
    val nWin = 1
    val nActor = 5
    val start = System.currentTimeMillis()
    val handle: HandleM[GCounter[Double, ProcId], Int, IntRange, Unit] =
      for {
        msg <- getMsg
        procId <- getProcId
        _ <- modifyCRDT[GCounter[Double, ProcId], Int, IntRange](gs =>
          gs.increase(procId)(msg)
        )
        _ <-
          if msg % (nMsg / nWin) == 0 then
            for {
              _ <- nextWindow[GCounter[Double, ProcId], Int, IntRange]
              v <- await[GCounter[Double, ProcId], Int, IntRange](
                msg / (nMsg / nWin)
              )
              _ <-
                if msg == nMsg && procId == 1 then
                  liftIO[GCounter[Double, ProcId], Int, IntRange, Unit] {
                    mvar.put(v.value)
                  }
                    >> liftContextIO[GCounter[Double, ProcId], Int, IntRange](
                      ctx =>
                        val endTime = System.currentTimeMillis()
                        val t = time.Span(
                          (endTime - start).doubleValue() / 1000,
                          time.Seconds
                        )
                        val avg = nMsg * 5 / ((endTime - start) / 1000)
                        ctx.log.warn(
                          s"\n\t\tTotal time: ${t.prettyString}" +
                            s"\n\t\tMessage per second: $avg"
                        )
                    )
                else pure(())
            } yield ()
          else pure(())
      } yield ()

    val stream = IntRange(0, nMsg + 1)

    val _ = ActorSystem(
      ActorMain.init[GCounter[Double, ProcId], Int, IntRange](
        GCounter.newGCounter[Double, ProcId]
      )(
        List.fill(nActor)(handle -> stream)
      ),
      "TestSystem"
    )

    assert(
      mvar.get() == nActor * Range(0, nMsg + 1).map(x => x.doubleValue()).sum()
    )
