package Types

import Types.Internal._
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

/** Main Actor
  *
  * Note that Message should be passed as (Int, M) so that it is dispatched to
  * Actor i.
  */
object ActorMain:
  def init[A, B, M](using x: CRDT[A, B, Int])(
      handles: List[HandleM[A, M, Unit]]
  ): Behavior[(Int, M)] =
    Behaviors.setup[(Int, M)]: context =>
      val len = handles.length
      val refs = handles
        .zip(Stream.from(1))
        .map((handle, id) =>
          context.spawn(WActorT.runWActorT(id)(handle), id.toString())
        )
        .zip(Stream.from(1))
        .map((a, b) => (b, a))
        .toMap()
      val idSet = Range(1, len + 1).toSet
      refs.values.foreach(ref =>
        ref ! UpdateIdSet(_ => idSet)
        ref ! UpdateRef(_ => refs)
      )
      process(refs)

  def process[A, M](
      refs: Map[Int, ActorRef[MsgT[A, Int, M]]]
  ): Behavior[(Int, M)] =
    Behaviors.receive[(Int, M)]: (context, m) =>
      refs.get(m._1) match
        case Some(ref) =>
          context.log.info(s"Received Msg: $m")
          ref ! Process(m._2)
          Behaviors.same
        case None =>
          context.log.error(s"Can not dispatch Msg: $m")
          Behaviors.same
