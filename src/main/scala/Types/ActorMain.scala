package Types

import Types.Internal.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

sealed trait Command

/** Main Actor
  *
  * Note that Message should be passed as (Int, M) so that it is dispatched to
  * Actor i.
  */
object ActorMain:
  def init[A, M](using x: CRDT[A])(initCRDT: A)(
      handles: List[(HandleM[A, M, Unit], Stream[M])]
  ): Behavior[Command] =
    Behaviors.setup[Command]: context =>
      val len = handles.length
      val refs = handles
        .zip(Stream.from(1))
        .map { case ((handle, stream), id) =>
          context.spawn(
            WActorT.runWActorT(initCRDT)(id)(handle)(stream),
            id.toString()
          )
        }
        .zip(Stream.from(1))
        .map((a, b) => (b, a))
        .toMap()
      val idSet = Range(1, len + 1).toSet
      // Notify references
      refs.values.foreach(ref =>
        ref ! UpdateIdSet(_ => idSet)
        ref ! UpdateRef(_ => refs)
      )
      // Bootstrap first message (if available)
      handles
        .map(x => x._2)
        .zip(refs.values)
        .foreach((stream, ref) =>
          stream.take(1).toList match
            case x :: _ => ref ! Process(x, stream.tail)
            case _      => ()
        )
      run()

  def run(): Behavior[Command] = Behaviors.empty
