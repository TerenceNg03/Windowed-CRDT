package Types

import Instances.ProcId
import Types.Internal.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors

sealed trait Command
case class ActorFailure[T](ref: ActorRef[T], id: ProcId) extends Command

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
      val handleRefs = handles
        .zip(Stream.from(1))
        .map { case ((handle, stream), id) =>
          val child = context.spawn[MsgT[A, M]](
            Actor.runActor(initCRDT),
            id.toString()
          )
          context.watchWith(child, ActorFailure(child, id))
          (id, (handle, stream, child))
        }
        .toMap()
      val idSet = Range(1, len + 1).toSet
      // Notify references
      handleRefs.values
        .map(x => x._3)
        .foreach(ref =>
          ref ! UpdateIdSet(_ => idSet)
          ref ! UpdateRef(_ => handleRefs.map(x => (x._1, x._2._3)).toMap)
        )
      // Bootstrap first message (if available)
      handleRefs
        .foreach { case (procId, (handle, stream, ref)) =>
          ref ! Deleagte(procId, stream, handle)
        }

      Behaviors
        .supervise(
          run(
            Range(1, handles.length + 1)
              .map(x => (x, List(x)))
              .toMap
          )(handleRefs)
        )
        .onFailure(SupervisorStrategy.stop)

  def run[A, M](delegating: Map[ProcId, List[ProcId]])(
      handleRefs: Map[
        Int,
        (HandleM[A, M, Unit], Stream[M], ActorRef[MsgT[A, M]])
      ]
  ): Behavior[Command] =
    Behaviors.receive: (ctx, msg) =>
      msg match
        case ActorFailure(ref, id) =>
          ctx.log.error(s"Actor $id is down!")
          val toRecover = delegating(id)
          val takeOver = delegating
            .map(x => x._1)
            .filter(_ != id)
            .headOption
            .getOrElse(
              throw new RuntimeException("All actor failed, unable to recover.")
            )
          toRecover.foreach(procId =>
            val (handle, stream, ref) = handleRefs(procId)
            ref ! Deleagte(procId, stream, handle)
          )
          run(
            delegating
              .updatedWith(id)(_ => None)
              .updated(takeOver, delegating(takeOver) ++ toRecover)
          )(handleRefs)
