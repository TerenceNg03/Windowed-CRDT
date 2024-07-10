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
  ignore should "handle 5* 200k stream" taggedAs (Slow) in:
    val mvar: MVar[Double] = newMVar
    val start = System.currentTimeMillis()
    val handle: HandleM[GCounter[Double, ProcId], Int, Unit] =
      for {
        msg <- getMsg
        procId <- getProcId
        _ <- modifyCRDT[GCounter[Double, ProcId], Int](gs =>
          gs.increase(procId)(msg)
        )
        _ <-
          if msg % 20_000 == 0 then
            for {
              _ <- nextWindow[GCounter[Double, ProcId], Int]
              v <- await[GCounter[Double, ProcId], Int](msg / 20_000)
              _ <-
                if msg == 200_000 && procId == 1 then
                  liftIO[GCounter[Double, ProcId], Int, Unit] {
                    mvar.put(v.value)
                  }
                    >> liftContextIO[GCounter[Double, ProcId], Int](ctx =>
                      val endTime = System.currentTimeMillis()
                      val t = time.Span(
                        (endTime - start).doubleValue() / 1000,
                        time.Seconds
                      )
                      val avg = 200_000 / ((endTime - start) / 1000)
                      ctx.log.info(
                        s"\n\t\tTotal time: ${t.prettyString}" +
                          s"\n\t\tMessage per second: $avg"
                      )
                    )
                else point(())
            } yield ()
          else point(())
      } yield ()

    val stream = LazyList.range(0, 200_001)

    val _ = ActorSystem(
      ActorMain.init[GCounter[Double, ProcId], Int](
        GCounter.newGCounter[Double, ProcId]
      )(
        List.fill(5)(handle -> stream)
      ),
      "TestSystem"
    )

    assert(mvar.get() == 5 * Range(0, 200_001).map(x => x.doubleValue()).sum())
