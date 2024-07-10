package Types

import Instances.ProcId
import Types.Internal.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import scala.annotation.tailrec

sealed trait Command
case class ActorFailure[T](id: ProcId) extends Command

private case class MainState[A, M](
    val initCRDT: A,
    val delegateMap: Map[ProcId, Option[(ActorRef[MsgT[A, M]], List[ProcId])]],
    val initial: Map[
      ProcId,
      (HandleM[A, M, Unit], LazyList[M])
    ]
)

/** Main Actor
  *
  * Note that Message should be passed as (Int, M) so that it is dispatched to
  * Actor i.
  */
object ActorMain:
  def init[A, M](using x: CRDT[A])(initCRDT: A)(
      handles: List[(HandleM[A, M, Unit], LazyList[M])]
  ): Behavior[Command] =
    Behaviors.setup[Command]: context =>
      val len = handles.length
      val initial = LazyList.from(1).zip(handles).toMap
      val delegateMap
          : Map[ProcId, Option[(ActorRef[MsgT[A, M]], List[ProcId])]] = handles
        .zip(LazyList.from(1))
        .map { case ((handle, stream), id) =>
          val child = context.spawn[MsgT[A, M]](
            Actor.runActor,
            id.toString()
          )
          context.watchWith(child, ActorFailure(id))
          (id, Some((child, List(id))))
        }
        .toMap()
      val idSet = Range(1, len + 1).toSet
      // Notify references
      delegateMap.values.flatten
        .map(x => x._1)
        .foreach(ref =>
          ref ! UpdateIdSet(_ => idSet)
          ref ! UpdateRef(_ => delegateMap.values.flatten.map(x => x._1).toSet)
        )
      // Assign replicas
      delegateMap.values.flatten
        .map(x => x._1)
        .zip(handles)
        .zipWithIndex
        .foreach { case ((ref, (handle, stream)), procId) =>
          ref ! Delegate(procId, (0, stream, initCRDT), handle)
        }

      Behaviors
        .supervise(
          run(
            MainState(
              initCRDT,
              delegateMap,
              initial
            )
          )
        )
        .onFailure(SupervisorStrategy.stop)

  @tailrec
  // If there is a node with more than 3 replicas
  // Split it into half
  def balanceWorkload[A, M](using
      x: CRDT[A]
  )(ms: MainState[A, M])(ctx: ActorContext[Command]): MainState[A, M] =
    val newM = ms.delegateMap
      .map((x, y) => y.map(z => (x, z._1, z._2)))
      .flatten
      .filter((_, _, l) => l.length >= 3)
      .headOption
      .map((fromId, ref, l) =>
        val toTransfer = l.take(l.length / 2)
        val targetId = ms.delegateMap.keySet.max
        val targetRef =
          ctx.spawn[MsgT[A, M]](Actor.runActor, targetId.toString())
        ctx.watchWith(targetRef, ActorFailure(targetId))
        
        // TODO: Notify all node to update reference list
        // TODO: Notify all node to merge into new child
        ???

        // TODO: log load balance
        val transfers =
          toTransfer.map(procId => (procId, ms.initial(procId))).toMap
        ref ! transferReplica[A, M](ms.initCRDT, transfers, targetRef)

        ms.delegateMap
          .updatedWith(fromId)(v =>
            Some(v.flatten.map((ref, _) => (ref, l.drop(l.length / 2))))
          )
          .updatedWith(targetId)(_ => Some(Some(targetRef, toTransfer)))
      )
    newM match
      case Some(m) => balanceWorkload(ms.copy(delegateMap = m))(ctx)
      case None    => ms

  def run[A, M](using
      x: CRDT[A]
  )(ms: MainState[A, M]): Behavior[Command] =
    Behaviors
      .supervise[Command](Behaviors.receive: (ctx, msg) =>
        msg match
          case ActorFailure(id) =>
            ctx.log.error(s"Actor $id is down!")
            val ms_ = ms
              .delegateMap(id)
              .map { (_, toRecover) =>
                val (takeOverId, takeoverRef) = ms.delegateMap
                  .filter((id_, l) => id_ != id && l.isDefined)
                  .map((x, y) => (x, y.get))
                  .minByOption((_, l) => l._2.length)
                  .map(x => x._1 -> x._2._1)
                  .getOrElse {
                    throw new RuntimeException(
                      "All actor failed, unable to recover."
                    )
                  }
                toRecover.foreach(procId =>
                  val (handle, stream) = ms.initial(procId)
                  takeoverRef ! Delegate(
                    procId,
                    (0, stream, ms.initCRDT),
                    handle
                  )
                )

                ms.copy(delegateMap =
                  ms.delegateMap
                    .updatedWith(id)(_ => None)
                    .updated(
                      takeOverId,
                      Some(
                        takeoverRef -> (ms
                          .delegateMap(takeOverId)
                          .get
                          ._2 ++ toRecover)
                      )
                    )
                )
              }
              .getOrElse(ms)

            run(balanceWorkload(ms_)(ctx))
      )
      .onFailure(SupervisorStrategy.stop)
