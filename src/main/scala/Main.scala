import Instances.{*, given}
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorSystem
import scala.util.Random

/** Use a grow-only set to construct a windowed CRDT. Here the message is simple
  * an integer that will be added to the set.
  */
val handleMain: HandleM[GSet[Int], Int, Unit] =
  for {
    msg <- getMsg
    _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
    _ <-
      if msg >= 5 then
        for
          _ <- nextWindow[GSet[Int], Int]
          v <- await[GSet[Int], Int](0)
          _ <- liftContextIO[GSet[Int], Int](ctx =>
            ctx.log.info(s"Process finished! Window 0's value: $v")
          )
        yield ()
      else point(())
  } yield ()

val handleRandomFail: HandleM[GSet[Int], Int, Unit] =
  for {
    msg <- getMsg
    _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
    _ <- nextWindow[GSet[Int], Int]
    _ <-
      if Random.nextDouble() > 0.75 then error("trigger crash")
      else point(())
  } yield ()

@main def hello(): Unit =
  // Here our message is an Int, but the system need to receive an (Int, Int) so
  // that it knows to whom this message should be sent.
  val _ = ActorSystem(
    ActorMain.init[GSet[Int], Int](Set.empty)(
      List(
        handleMain -> LazyList(1, 3, 5),
        handleRandomFail -> LazyList(2, 4, 6),
        handleRandomFail -> LazyList(10, 20, 30)
      )
    )(3),
    "TestSystem"
  )
