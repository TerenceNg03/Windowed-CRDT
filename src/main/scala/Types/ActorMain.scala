package Types

import Instances.ProcId
import Instances.Wcrdt.newWcrdt
import Types.HandleM.pure
import Types.Internal.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors

sealed trait Command
case class ActorFailure[T](id: ProcId) extends Command
case class FatalFailure(id: ProcId, msg: String) extends Command

private case class MainState[A, M, S](
    val initCRDT: A,
    val procMap: Map[ProcId, Option[(ActorRef[MsgT[A, M, S]], ProcId)]],
    val initial: Map[
      ProcId,
      (HandleM[A, M, S, Unit], S)
    ]
)

/** Main Actor
  *
  * Note that Message should be passed as (Int, M) so that it is dispatched to
  * Actor i.
  */
object ActorMain:
  def init[A, M, S](using x: CRDT[A], y: PersistStream[S, M])(initCRDT: A)(
      handles: List[(HandleM[A, M, S, Unit], S)]
  ): Behavior[Command] = ???
  def withDispatcher[A, M, S](using x: CRDT[A], y: PersistStream[S, M])(
      initCRDT: A
  )(
      handles: List[(HandleM[A, M, S, Unit], S)]
  )(dispatcher: DispatcherSelector): Behavior[Command] =
    Behaviors.setup[Command]: ctx =>
      val len = handles.length
      val initial = LazyList.from(1).zip(handles).toMap
      val procMap: Map[ProcId, Option[(ActorRef[MsgT[A, M, S]], ProcId)]] =
        initial
          .map { case (id, (handle, stream)) =>
            val child = ctx.spawn[MsgT[A, M, S]](
              Actor.runActor(initCRDT, id, ctx.self),
              id.toString(),
              dispatcher
            )
            ctx.watchWith(child, ActorFailure(id))
            (id, Some((child, id)))
          }
          .toMap
          .updated(
            0, {
              val child = ctx.spawn[MsgT[A, M, S]](
                Actor.runActor(initCRDT, 0, ctx.self),
                0.toString(),
                dispatcher
              )
              ctx.watchWith(child, ActorFailure(0))
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
      procMap(0).get._1 ! Exec(
        0,
        (0, summon[PersistStream[S, M]].empty, newWcrdt(initCRDT)),
        pure(())
      )

      Behaviors
        .supervise(
          run(
            MainState(
              initCRDT,
              procMap,
              initial
            )
          )(dispatcher)
        )
        .onFailure(SupervisorStrategy.stop)

  def run[A, M, S](using
      x: CRDT[A],
      y: PersistStream[S, M]
  )(ms: MainState[A, M, S])(dispatcher: DispatcherSelector): Behavior[Command] =
    Behaviors
      .supervise[Command](Behaviors.receive: (ctx, msg) =>
        msg match
          case FatalFailure(id, msg) =>
            ctx.log.error(
              s"Fatal failure: Node $id (Replica ${ms.procMap.get(id)}) --\n\t$msg"
            )
            throw new RuntimeException("Fatal failure!")
          case ActorFailure(id) if ms.procMap.get(id).isDefined =>
            if id == 0 then
              throw new RuntimeException("Guardian node 0 failed!")
            val procId = ms.procMap(id).get._2
            ctx.log.error(s"Node $id (Replica $procId) is down!")
            val childId = ms.procMap.keySet.max + 1
            ctx.log.info(s"New node created: $childId for Replica $procId")
            val childRef =
              ctx.spawn[MsgT[A, M, S]](
                Actor.runActor(ms.initCRDT, childId, ctx.self),
                childId.toString(),
                dispatcher
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
            run(ms = ms_)(dispatcher)
          case _ => run(ms)(dispatcher)
      )
      .onFailure(SupervisorStrategy.stop)
