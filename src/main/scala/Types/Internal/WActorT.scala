package Types.Internal

import Instances.ProcID
import Instances.Wcrdt
import Instances.given
import Types.*
import Types.given
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scalaz.IndexedStateT.StateMonadTrans
import scalaz.MonadTrans
import scalaz.Scalaz.ToBindOps

case class WActorState[A, M](
    val wcrdt: Wcrdt[A, Stream[M]],
    val actorId: ProcID,
    val actorIdSet: Set[ProcID],
    val actorRefs: Map[Int, ActorRef[MsgT[A, M]]],
    // Awaits: #Window, Message waiting, Monad Operation to be continued, Following Messages
    val queuedHandleM: Option[(Int, M, A => HandleM[A, M, Unit])],
    val stream: Stream[M]
)

/** Windowed CRDT Actor Transformer
  */
object WActorT:
  /** Run a windowed crdt actor transformer
    *
    * Behavior can be modified by returning Right in handle
    *
    * @param x
    * @param wcrdt
    * @param handle
    * @return
    */
  def runWActorT[A, M](using
      x: CRDT[A]
  )(initCRDT: A)(
      procID: ProcID
  )(handle: HandleM[A, M, Unit])(stream: Stream[M]): Behavior[MsgT[A, M]] =
    runWActorT_(
      WActorState(
        Wcrdt.newWcrdt(procID)(initCRDT)(stream),
        procID,
        Set.empty,
        Map.empty,
        None,
        stream
      )
    )(handle >> HandleM.processNext)

  def runWActorT_[A, M](using
      x: CRDT[A]
  )(s: WActorState[A, M])(
      handle: HandleM[A, M, Unit]
  ): Behavior[MsgT[A, M]] =
    val processResult = (x: HandleResult[A, M, Unit]) =>
      x match
        case UpdateCRDT(v, _) =>
          // BroadCast finished window
          if v.window.v > s.wcrdt.window.v then
            s.actorRefs.foreach((id, ref) =>
              if id != s.actorId then ref ! Merge(v)
            )
          runWActorT_(s.copy(wcrdt = v))(handle)
        // No update to CRDT
        case Pass(_) => runWActorT_(s)(handle)
        // Backdoor to replace behavior
        case ModifyBehavior(b) => b
        // Waiting for a window, later operation queued
        case AwaitWindow(w, msg, crdt, next) =>
          val s_ = s.copy(
            wcrdt = crdt,
            queuedHandleM =
              Some((w, msg, x => summon[MonadTrans[?]].liftM(next(x))))
          )
          runWActorT_(s_)(handle)

    Behaviors.receive[MsgT[A, M]]: (context, msg) =>
      msg match
        case Merge(v) =>
          val wcrdt = s.wcrdt \/ v
          val s_ = s.copy(wcrdt = wcrdt)
          // Check if we had the window value if there is an await
          // Resume execution if we had
          s_.queuedHandleM match
            case None => runWActorT_(s_)(handle)
            case Some(w, m, hm) =>
              s_.wcrdt.query(w)(s_.actorIdSet) match
                case None => runWActorT_(s_)(handle)
                case Some(crdt) =>
                  val result =
                    hm(crdt).eval((context, m, s_)).runHandleM_(s_.wcrdt)
                  processResult(result)
        case UpdateIdSet(f) =>
          runWActorT_(s.copy(actorIdSet = f(s.actorIdSet)))(handle)
        case UpdateRef(f) =>
          runWActorT_(s.copy(actorRefs = f(s.actorRefs)))(handle)
        case Process(m, stream) =>
          // If there are awaits, postpone message handling
          s.queuedHandleM match
            case Some(w, m_, hm) =>
              val s_ = s.copy(queuedHandleM =
                Some(
                  w,
                  m_,
                  x => hm(x) >> HandleM.replaceMsg(m)(stream) >> handle
                )
              )
              runWActorT_(s_)(handle)
            case None =>
              val result = handle.eval((context, m, s)).runHandleM_(s.wcrdt)
              processResult(result)
