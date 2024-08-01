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
val handleMain: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
  for {
    msg <- getMsg
    _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
    _ <- nextWindow[GSet[Int], Int, ListStream[Int]]
    _ <-
      if msg >= 5 then
        for
          v <- await[GSet[Int], Int, ListStream[Int]](2)
          _ <- liftContextIO[GSet[Int], Int, ListStream[Int]](ctx =>
            ctx.log.info(s"Process finished! Window 0's value: $v")
          )
        yield ()
      else pure(())
  } yield ()

val handleRandomFail: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
  for {
    msg <- getMsg
    _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
    _ <-
      if Random.nextDouble() > 0.6 then error("trigger crash")
      else pure(())
    _ <- nextWindow[GSet[Int], Int, ListStream[Int]]
  } yield ()

def run(): Unit =
  // Here our message is an Int, but the system need to receive an (Int, Int) so
  // that it knows to whom this message should be sent.
  val _ = ActorSystem(
    ActorMain.init[GSet[Int], Int, ListStream[Int]](Set.empty)(
      List(
        handleMain -> ListStream(List(1, 3, 5)),
        handleRandomFail -> ListStream(List(2, 4, 6)),
        handleRandomFail -> ListStream(List(10, 20, 30))
      )
    ),
    "System"
  )
