import Instances.{*, given}
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorSystem

/** Use a grow-only set to construct a windowed CRDT. Here the message is simple
  * an integer that will be added to the set.
  */
val handle1: HandleM[GSet[Int], Int, Unit] =
  for {
    msg <- getMsg
    _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
    _ <-
      if msg >= 5 then
        for
          _ <- nextWindow[GSet[Int], Int]
          v <- await[GSet[Int], Int](0)
          _ <- liftContextIO[GSet[Int], Int](ctx =>
            ctx.log.info(s"Window 0's value: $v")
          )
        yield ()
      else point(())
  } yield ()

val handle2: HandleM[GSet[Int], Int, Unit] =
  for {
    msg <- getMsg
    _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
    _ <-
      if msg >= 6 then nextWindow[GSet[Int], Int]
      else point(())
  } yield ()

@main def hello(): Unit =
  // Here our message is an Int, but the system need to receive an (Int, Int) so
  // that it knows to whom this message should be sent.
  val system = ActorSystem(
    ActorMain.init[GSet[Int], Int](Set.empty)(
      List(handle1 -> Stream(1, 3, 5), handle2 -> Stream(2, 4, 6))
    ),
    "TestSystem"
  )
