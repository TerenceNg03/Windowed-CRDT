import Types.ActorMain
import org.apache.pekko.actor.typed.ActorSystem
import Instances.{given, *}
import Types.Handle
import Types.Propagate

val handle: Handle[GSet[Int], Int] = context =>
  x =>
    gs =>
      val gs_ = gs.update(_ + x).nextWindow()
      context.log.info(s"Processor ${gs_.procID}: New value: ${gs_.local}")
      Propagate(gs_)

@main def hello(): Unit =
  val system: ActorSystem[(Int, Int)] =
    ActorSystem(ActorMain.init(List(handle, handle, handle)), "TestSystem")

  system ! (1, 2)
  system ! (1, 3)
  system ! (2, 4)
  system ! (3, 2)
