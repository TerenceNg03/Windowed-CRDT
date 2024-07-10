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

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Random

import flatspec.*
import matchers.*

class ActorCrushPressureTest extends AnyFlatSpec with should.Matchers:
  it should "handle 50 crashes" taggedAs (Slow) in:
    val mvar: MVar[Int] = newMVar
    val counter = new AtomicInteger(50)
    val handle: HandleM[GCounter[Int, ProcId], Int, Unit] =
      for {
        msg <- getMsg
        procId <- getProcId
        _ <- modifyCRDT[GCounter[Int, ProcId], Int](gs =>
          gs.increase(procId)(msg)
        )
        _ <-
          if msg % 50 == 0 && Random.nextDouble() > 0.7 then
            for {
              flag <- liftIO(counter.getAndDecrement())
              _ <-
                if flag >= 0 then error("TestCrash")
                else point(())
            } yield ()
          else point(())
        _ <-
          if msg % 200 == 0 then
            for {
              _ <- nextWindow[GCounter[Int, ProcId], Int]
              v <- await[GCounter[Int, ProcId], Int](msg / 200)
              _ <-
                if msg == 1000 && procId == 1 then
                  liftIO[GCounter[Int, ProcId], Int, Unit]((mvar.put(v.value)))
                else point(())
            } yield ()
          else point(())
      } yield ()

    val stream = LazyList.range(0, 1001)

    val _ = ActorSystem(
      ActorMain.init[GCounter[Int, ProcId], Int](
        GCounter.newGCounter[Int, ProcId]
      )(
        List.fill(100)(handle -> stream)
      ),
      "TestSystem"
    )

    assert(mvar.get() == 100 * Range(0, 1001).sum())
