package Types.Internal

import Instances.*
import Instances.given
import Types.*
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import Types.HandleM.point

case class ActorState[A, M](
    val wcrdt: Wcrdt[A, LazyList[M]],
    val actorIdSet: Set[ProcId],
    val actorRefs: Set[ActorRef[MsgT[A, M]]],
    // Awaits: #Window, Message waiting, Monad Operation to be continued, Following Messages
    val queuedHandleM: Option[
      (WindowId, M, LazyList[M], A => HandleM[A, M, Unit])
    ],
    val handle: HandleM[A, M, Unit],
    val nodeId: ProcId,
    val procId: ProcId,
    val timers: TimerScheduler[MsgT[A, M]]
)

object ActorState:
  def newActorState[A, M](
      initCRDT: A,
      nodeId: ProcId,
      timers: TimerScheduler[MsgT[A, M]]
  ) =
    ActorState(
      Wcrdt.newWcrdt[A, LazyList[M]](initCRDT),
      Set.empty,
      Set.empty,
      None,
      handle = point(()),
      nodeId = nodeId,
      procId = -1,
      timers
    )

/** Windowed CRDT Actor Transformer
  */
object Actor:
  /** Run a windowed crdt actor transformer
    *
    * Behavior can be modified by returning Right in handle
    *
    * @param x
    * @param Wcrdt
    * @param handle
    * @return
    */
  def runActor[A, M](using
      x: CRDT[A]
  )(
      initCRDT: A,
      nodeId: ProcId
  ): Behavior[MsgT[A, M]] =
    Behaviors.withTimers(timers =>
      processMsgInit(
        ActorState.newActorState(
          initCRDT = initCRDT,
          nodeId = nodeId,
          timers = timers
        )
      )
    )

  def processMsgInit[A, M](using
      x: CRDT[A]
  )(s: ActorState[A, M]): Behavior[MsgT[A, M]] =
    Behaviors.receive[MsgT[A, M]]: (ctx, msg) =>
      val s_ = execMsg(s)(ctx, msg)
      processMsg(s_)

  def processMsg[A, M](using
      x: CRDT[A]
  )(s: ActorState[A, M]): Behavior[MsgT[A, M]] =
    Behaviors.receive[MsgT[A, M]]: (ctx, msg) =>
      val s_ = execMsg(s)(ctx, msg)
      processMsg(s_)

  def resultToState[A, M]: HandleResult[A, M, Unit] => ActorState[A, M] =
    // Continue handle next message
    case Continue(s, _) => s.state
    // Waiting for a window, later operation queued
    case AwaitWindow(w, msg, stream, s, next) =>
      val s_ = s.state.copy(queuedHandleM = Some(w, msg, stream, next))
      s_

  def execMsg[A, M](using
      x: CRDT[A]
  )(
      s: ActorState[A, M]
  ): (ActorContext[MsgT[A, M]], MsgT[A, M]) => ActorState[A, M] =
    (ctx, msg) =>
      msg match
        case RequestMerge(nodeId, procId, ref) =>
          ctx.log.debug(
            s"Node ${s.nodeId} (Replica ${s.procId}) get a merge request from Node $nodeId (Replica $procId), sending"
          )
          ref ! Merge(s.nodeId, s.procId, s.wcrdt)
          s
        case transferReplica(initCRDT, (procId, handle, stream), ref) =>
          assert(s.procId == 0)
          val restartPoint = s.wcrdt
            .latestWindow(s.actorIdSet)
            .map((w, a) =>
              (
                w + 1,
                s.wcrdt.globalProgress(w)._2(procId).v._2,
                s.wcrdt.copy(innerCRDT = LocalWin(a), window = LocalWin(w + 1))
              )
            )
            .getOrElse(
              (
                0,
                stream,
                s.wcrdt
                  .copy(innerCRDT = LocalWin(initCRDT), window = LocalWin(0))
              )
            )
          ctx.log.info(
            s"Guardian recovered Replica $procId," +
              s"reset window to ${restartPoint._1}"
          )
          ref ! Exec(procId, restartPoint, handle)
          s
        case Exec(
              procId,
              (remoteWindow, remoteStream, remoteCRDT),
              handle
            ) =>
          val (w, wcrdt_) = s.wcrdt.exec(procId)(s.actorIdSet)(
            remoteWindow
          )(remoteCRDT.innerCRDT.v)
          val wcrdt = wcrdt_ \/ remoteCRDT
          ctx.log.info(
            s"Node ${s.nodeId} (Replica ${s.procId}) will execute Replica $procId, window reset to#$w"
          )
          val stream: LazyList[M] =
            if remoteWindow >= w then remoteStream
            else wcrdt.globalProgress(w - 1)._2(procId).v._2

          stream.take(1).toList match
            case x :: _ =>
              ctx.log.debug(
                s"Replica $procId sends initial message after delegation: $x"
              )
              ctx.self ! Process(x, stream.tail)
            case _ =>
              ctx.log.debug(
                s"Replica $procId is delegated but stream has finished"
              )

          s.copy(
            wcrdt = wcrdt,
            queuedHandleM = None,
            procId = procId,
            handle = handle
          )

        case Merge(fromNodeId, fromProcId, v) =>
          val wcrdt =
            if fromNodeId != s.nodeId then s.wcrdt \/ v
            else s.wcrdt
          val s_ = s.copy(wcrdt = wcrdt)
          ctx.log.debug(
            s"Node ${s.nodeId} (Replica ${s.procId}) (finished#${s.wcrdt.window.v - 1})" +
              s" is merging from Node ${fromNodeId} (Replica ${fromProcId}) (finished#${v.window.v - 1})"
          )
          // Check if we had the window value if there is an await
          // Resume execution if we had
          s_.queuedHandleM match
            case None => s_
            case Some(w, m, stream, hm) =>
              s_.wcrdt.query(w)(s_.actorIdSet) match
                case None =>
                  ctx.log.debug(
                    s"Replica ${s_.procId} is still waiting for window#$w"
                  )
                  s_
                case Some(crdt) =>
                  ctx.log.info(
                    s"Replica ${s_.procId} is continuing, previously waiting for window#$w"
                  )
                  val result =
                    hm(crdt).runHandleM(
                      HandleState(
                        m,
                        stream,
                        s_.copy(queuedHandleM = None),
                        ctx
                      )
                    )
                  resultToState(result)

        case SetIdSet(set) =>
          s.copy(actorIdSet = set)
        case SetRefs(refs) =>
          s.copy(actorRefs = refs)
        case Process(m, stream) =>
          ctx.log.debug(s"Replica ${s.procId} gets a new message: $m")
          // If there are awaits, postpone message handling
          s.queuedHandleM match
            case Some(w, m_, stream_, hm) =>
              ctx.log.debug(
                s"Replica ${s.procId} is waiting. New message queued up."
              )
              val s_ = s.copy(queuedHandleM =
                Some(
                  (
                    w,
                    m_,
                    stream_,
                    x =>
                      hm(x) >> HandleM.prepareHandleNewMsg(m)(
                        stream
                      ) >> s.handle
                  )
                )
              )
              s_
            case None =>
              val result =
                (HandleM.prepareHandleNewMsg(m)(stream) >> s.handle)
                  .runHandleM(HandleState(m, stream, s, ctx))
              resultToState(result)
