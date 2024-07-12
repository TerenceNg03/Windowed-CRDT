package Types

import Instances.ProcId
import Types.Internal.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import Instances.Wcrdt.newWcrdt
import Types.HandleM.point

sealed trait Command
case class ActorFailure[T](id: ProcId) extends Command

private case class MainState[A, M](
    val initCRDT: A,
    val procMap: Map[ProcId, Option[(ActorRef[MsgT[A, M]], ProcId)]],
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
      val procMap: Map[ProcId, Option[(ActorRef[MsgT[A, M]], ProcId)]] =
        initial
          .map { case (id, (handle, stream)) =>
            val child = context.spawn[MsgT[A, M]](
              Actor.runActor(initCRDT, id),
              id.toString()
            )
            context.watchWith(child, ActorFailure(id))
            (id, Some((child, id)))
          }
          .toMap
          .updated(
            0, {
              val child = context.spawn[MsgT[A, M]](
                Actor.runActor(initCRDT, 0),
                0.toString()
              )
              context.watchWith(child, ActorFailure(0))
              Some(child, 0)
            }
          )
      val idSet = Range(1, len + 1).toSet
      // Notify references
      procMap.values.flatten
        .map(x => x._1)
        .foreach(ref =>
          ref ! SetIdSet(idSet)
          ref ! SetRefs(procMap.values.flatten.map(x => x._1).toSet)
        )
      // Assign Replica
      procMap
        .map((x, y) => y.map((a, b) => (x, a, b)))
        .flatten
        .filter((x, _, _) => x != 0)
        .foreach { case (procId, ref, _) =>
          ref ! Exec(
            procId,
            (0, initial(procId)._2, newWcrdt(initCRDT)),
            initial(procId)._1
          )
        }

      // Guardian node #0 only receive merges
      procMap(0).get._1 ! Exec(0, (0, LazyList.empty, newWcrdt(initCRDT)), point(()))

      Behaviors
        .supervise(
          run(
            MainState(
              initCRDT,
              procMap,
              initial
            )
          )
        )
        .onFailure(SupervisorStrategy.stop)

  def run[A, M](using
      x: CRDT[A]
  )(ms: MainState[A, M]): Behavior[Command] =
    Behaviors
      .supervise[Command](Behaviors.receive: (ctx, msg) =>
        msg match
          case ActorFailure(id) if ms.procMap.get(id).isDefined =>
            if id == 0 then
              throw new RuntimeException("Guardian node 0 failed!")
            val procId = ms.procMap(id).get._2
            ctx.log.error(s"Node $id (Replica $procId) is down!")
            val childId = ms.procMap.keySet.max + 1
            ctx.log.info(s"New node created: $childId for Replica $procId")
            val childRef =
              ctx.spawn[MsgT[A, M]](
                Actor.runActor(ms.initCRDT, childId),
                childId.toString()
              )
            ctx.watchWith(childRef, ActorFailure(childId))

            childRef ! SetIdSet(ms.initial.keySet)
            val ms_ = ms.copy(
              procMap = ms.procMap
                .updated(id, None)
                .updated(childId, Some((childRef, procId)))
            )

            val newRefs =
              ms_.procMap.map(x => x._2.map(x => x._1)).flatten.toSet
            newRefs.foreach(ref => ref ! SetRefs(newRefs))

            val (handle, stream) = ms_.initial(procId)
            ms.procMap(0).get._1 ! transferReplica(
              ms_.initCRDT,
              (procId, handle, stream),
              childRef
            )
            run(ms = ms_)
          case _ => run(ms)
      )
      .onFailure(SupervisorStrategy.stop)
