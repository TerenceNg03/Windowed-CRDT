import Instances.{_, given}
import Types.ActorMain
import Types.HandleM
import Types.HandleM._
import Types.given
import org.apache.pekko.actor.typed.ActorSystem

/** Use a grow-only set to construct a windowed CRDT. Here the message is simple
  * an integer that will be added to the set.
  */
val handle: HandleM[GSet[Int], Int, Unit] =
  for {
    x <- getMsg
    gs_ <- modifyCRDT[GSet[Int], Int](gs => gs.update(_ + x).nextWindow())
    _ <- liftContextIO[GSet[Int], Int](context =>
      context.log.info(s"Processor ${gs_.procID}: New value: ${gs_.local}")
    )
  } yield ()

@main def hello(): Unit =
  // Here our message is an Int, but the system need to receive an (Int, Int) so
  // that it knows to whom this message should be sent.
  val system: ActorSystem[(Int, Int)] =
    ActorSystem(ActorMain.init(List(handle, handle, handle)), "TestSystem")

  // system ! (i, j)
  // Send a message to Actor i with payload j
  system ! (1, 2)
  system ! (1, 3)
  system ! (2, 4)
  system ! (3, 2)
