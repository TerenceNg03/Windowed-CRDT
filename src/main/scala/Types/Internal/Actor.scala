package Types.Internal

import Instances.*
import Instances.given
import Types.*
import Types.HandleM.pure
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

case class ActorState[A, M, S](
    val wcrdt: Wcrdt[A, S],
    val actorIdSet: Set[ProcId],
    val mainRef: ActorRef[Command],
    val actorRefs: Set[ActorRef[MsgT[A, M, S]]],
    // Awaits: #Window, Message waiting, Monad Operation to be continued, Following Messages
    val queuedHandleM: Option[
      (WindowId, M, S, A => HandleM[A, M, S, Unit])
    ],
    val handle: HandleM[A, M, S, Unit],
    val nodeId: ProcId,
    val procId: ProcId,
    val timers: TimerScheduler[MsgT[A, M, S]]
)

object ActorState:
  def newActorState[A, M, S](
      initCRDT: A,
      nodeId: ProcId,
      mainRef: ActorRef[Command],
      timers: TimerScheduler[MsgT[A, M, S]]
  ) =
    ActorState(
      Wcrdt.newWcrdt[A, S](initCRDT),
      Set.empty,
      mainRef,
      Set.empty,
      None,
      handle = pure(()),
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
  def runActor[A, M, S](using
      x: CRDT[A],
      y: PersistStream[S, M]
  )(
      initCRDT: A,
      nodeId: ProcId,
      mainRef: ActorRef[Command]
  ): Behavior[MsgT[A, M, S]] =
    Behaviors.withTimers(timers =>
      processMsgInit(
        ActorState.newActorState(
          initCRDT = initCRDT,
          nodeId = nodeId,
          mainRef = mainRef,
          timers = timers
        )
      )
    )

  def processMsgInit[A, M, S](using
      x: CRDT[A],
      y: PersistStream[S, M]
  )(s: ActorState[A, M, S]): Behavior[MsgT[A, M, S]] =
    Behaviors.receive[MsgT[A, M, S]]: (ctx, msg) =>
      val s_ = execMsg(s)(ctx, msg)
      processMsg(s_)

  def processMsg[A, M, S](using
      x: CRDT[A],
      y: PersistStream[S, M]
  )(s: ActorState[A, M, S]): Behavior[MsgT[A, M, S]] =
    Behaviors.receive[MsgT[A, M, S]]: (ctx, msg) =>
      val s_ = execMsg(s)(ctx, msg)
      processMsg(s_)

  def resultToState[A, M, S]
      : HandleResult[A, M, S, Unit] => ActorState[A, M, S] =
    // Continue handle next message
    case Continue(s, _) => s.state
    // Waiting for a window, later operation queued
    case AwaitWindow(w, msg, stream, s, next) =>
      val s_ = s.state.copy(queuedHandleM = Some(w, msg, stream, next))
      s_

  def execMsg[A, M, S](using
      x: CRDT[A],
      y: PersistStream[S, M]
  )(
      s: ActorState[A, M, S]
  ): (ActorContext[MsgT[A, M, S]], MsgT[A, M, S]) => ActorState[A, M, S] =
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
          val stream: S =
            if remoteWindow >= w then remoteStream
            else wcrdt.globalProgress(w - 1)._2(procId).v._2

          stream.next match
            case (Some(x), ns) =>
              ctx.log.debug(
                s"Replica $procId sends initial message after delegation: $x"
              )
              ctx.self ! Process(x, ns)
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
