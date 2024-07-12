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
    val nWin = 5
    val start = System.currentTimeMillis()
    val handle: HandleM[GCounter[Double, ProcId], Int, Unit] =
      for {
        msg <- getMsg
        procId <- getProcId
        _ <- modifyCRDT[GCounter[Double, ProcId], Int](gs =>
          gs.increase(procId)(msg)
        )
        _ <-
          if msg % (nMsg / nWin) == 0 then
            for {
              _ <- nextWindow[GCounter[Double, ProcId], Int]
              v <- await[GCounter[Double, ProcId], Int](msg / (nMsg / nWin))
              _ <-
                if msg == nMsg && procId == 1 then
                  liftIO[GCounter[Double, ProcId], Int, Unit] {
                    mvar.put(v.value)
                  }
                    >> liftContextIO[GCounter[Double, ProcId], Int](ctx =>
                      val endTime = System.currentTimeMillis()
                      val t = time.Span(
                        (endTime - start).doubleValue() / 1000,
                        time.Seconds
                      )
                      val avg = nMsg * 5/ ((endTime - start) / 1000)
                      ctx.log.warn(
                        s"\n\t\tTotal time: ${t.prettyString}" +
                          s"\n\t\tMessage per second: $avg"
                      )
                    )
                else point(())
            } yield ()
          else point(())
      } yield ()

    val stream = LazyList.range(0, nMsg + 1)

    val _ = ActorSystem(
      ActorMain.init[GCounter[Double, ProcId], Int](
        GCounter.newGCounter[Double, ProcId]
      )(
        List.fill(5)(handle -> stream)
      ),
      "TestSystem"
    )

    assert(mvar.get() == 5 * Range(0, nMsg + 1).map(x => x.doubleValue()).sum())
