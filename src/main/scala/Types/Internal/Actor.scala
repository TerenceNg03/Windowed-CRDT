package Types.Internal

import Instances.ProcId
import Instances.SharedWcrdt
import Instances.given
import Types.*
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

case class ActorState[A, M](
    val sharedWcrdt: SharedWcrdt[A, Stream[M]],
    val actorIdSet: Set[ProcId],
    val actorRefs: Map[ProcId, ActorRef[MsgT[A, M]]],
    // Awaits: #Window, Message waiting, Monad Operation to be continued, Following Messages
    val queuedHandleM: Map[
      ProcId,
      (Int, M, Stream[M], A => HandleM[A, M, Unit])
    ],
    val delegated: Map[ProcId, HandleM[A, M, Unit]]
):
  def delegatedIds: Set[ProcId] = delegated.keySet

object ActorState:
  def newActorState[A, M](initCRDT: A) =
    ActorState(
      SharedWcrdt.newSharedWcrdt[A, Stream[M]](initCRDT),
      Set.empty,
      Map.empty,
      Map.empty,
      Map.empty
    )

/** Windowed CRDT Actor Transformer
  */
object Actor:
  /** Run a windowed crdt actor transformer
    *
    * Behavior can be modified by returning Right in handle
    *
    * @param x
    * @param SharedWcrdt
    * @param handle
    * @return
    */
  def runActor[A, M](using
      x: CRDT[A]
  )(initCRDT: A): Behavior[MsgT[A, M]] =
    processMsg(ActorState.newActorState(initCRDT))

  def processMsg[A, M](using
      x: CRDT[A]
  )(s: ActorState[A, M]): Behavior[MsgT[A, M]] =
    Behaviors.receive[MsgT[A, M]]: (ctx, msg) =>
      val s_ = execMsg(s)(ctx, msg)
      processMsg(s_)

  def resultToState[A, M]
      : ProcId => HandleResult[A, M, Unit] => ActorState[A, M] =
    procId => // Continue handle next message
      case Continue(s, _) => s.state
      // Waiting for a window, later operation queued
      case AwaitWindow(w, msg, stream, s, next) =>
        val s_ = s.state.copy(queuedHandleM =
          s.state.queuedHandleM.updated(procId, (w, msg, stream, next))
        )
        s_

  def execMsg[A, M](using
      x: CRDT[A]
  )(
      s: ActorState[A, M]
  ): (ActorContext[MsgT[A, M]], MsgT[A, M]) => ActorState[A, M] =
    (context, msg) =>
      msg match
        case Deleagte(procId, defaultStream, handle) =>
          val (w, wcrdt) = s.sharedWcrdt.delegate(procId)(s.actorIdSet)
          context.log.debug(
            s"Actor group ${s.delegatedIds} will delegate Actor $procId, window reset to#$w"
          )
          val stream: Stream[M] =
            if w == 0 then defaultStream
            else wcrdt.globalProgress(w - 1)._2(procId).v._2

          stream.take(1).toList match
            case x :: _ =>
              context.log.debug(
                s"Actor $procId sends initial message after delegation: $x"
              )
              context.self ! Process((procId, x), stream.tail)
            case _ =>
              context.log.debug(
                s"Actor $procId is delegated but stream has finished"
              )

          s.copy(
            sharedWcrdt = wcrdt,
            delegated = s.delegated.updated(procId, handle)
          )

        case Merge(fromIds, v) =>
          val sharedWcrdt = s.sharedWcrdt \/ v
          val s_ = s.copy(sharedWcrdt = sharedWcrdt)
          context.log.debug(
            s"Actor group ${s.delegatedIds} (finished#${s.sharedWcrdt.windows.v
                .mapValues(_ - 1)
                .toMap
                .toSet})" +
              s" is merging from Actor group ${fromIds} (finished#${v.windows.v
                  .mapValues(_ - 1)
                  .toMap
                  .toSet})"
          )
          // Check if we had the window value if there is an await
          // Resume execution if we had
          var s__ = s_
          s_.queuedHandleM.foreach { case (procId, (w, m, stream, hm)) =>
            s__.sharedWcrdt.query(w)(s__.actorIdSet) match
              case None =>
                context.log.debug(
                  s"Actor ${procId} is still waiting for window#$w"
                )
              case Some(crdt) =>
                context.log.debug(
                  s"Actor ${procId} is continuing, previously waiting for window#$w"
                )
                val result =
                  hm(crdt).runHandleM(
                    HandleState(
                      m,
                      stream,
                      procId,
                      s__.copy(queuedHandleM =
                        s__.queuedHandleM.updatedWith(procId)(_ => None)
                      ),
                      context
                    )
                  )
                s__ = resultToState(procId)(result)
          }
          s__
        case UpdateIdSet(f) =>
          s.copy(actorIdSet = f(s.actorIdSet))
        case UpdateRef(f) =>
          s.copy(actorRefs = f(s.actorRefs))
        case Process((targetId, m), stream)
            if s.delegatedIds.contains(targetId) =>
          context.log.debug(s"Actor ${targetId} gets a new message: $m")
          // If there are awaits, postpone message handling
          val handle = s.delegated(targetId)
          s.queuedHandleM.get(targetId) match
            case Some(w, m_, stream_, hm) =>
              context.log.debug(
                s"Actor ${targetId} is waiting. New message will be queued up."
              )
              val s_ = s.copy(queuedHandleM =
                s.queuedHandleM.updated(
                  targetId,
                  (
                    w,
                    m_,
                    stream_,
                    x =>
                      hm(x) >> HandleM.prepareHandleNewMsg(targetId)(m)(
                        stream
                      ) >> handle
                  )
                )
              )
              s_
            case None =>
              val result =
                (HandleM.prepareHandleNewMsg(targetId)(m)(stream) >> handle)
                  .runHandleM(HandleState(m, stream, targetId, s, context))
              resultToState(targetId)(result)
        case Process(_, _) => s
