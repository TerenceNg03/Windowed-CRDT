package Types

import Instances.Wcrdt
import Instances.WindowID
import Types.Internal.*
import cats.*
import cats.syntax.all.*
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext

sealed trait HandleResult[A, M, C]
case class ModifyBehavior[A, M, C](b: Behavior[MsgT[A, M]])
    extends HandleResult[A, M, C]
case class Continue[A, M, C](state: HandleState[A, M], v: C)
    extends HandleResult[A, M, C]
case class AwaitWindow[A, M, C](
    w: Int,
    msg: M,
    state: HandleState[A, M],
    next: A => HandleM[A, M, C]
) extends HandleResult[A, M, C]

private type HandleState[A, M] = (
    M,
    ActorState[A, M],
    ActorContext[MsgT[A, M]]
)
class HandleM[A, M, C] private[Types] (
    val runHandleM: HandleState[A, M] => HandleResult[A, M, C]
)

given [A, M, C]: Functor[[C] =>> HandleM[A, M, C]] with
  def map[C, B](fa: HandleM[A, M, C])(f: C => B): HandleM[A, M, B] =
    for {
      x <- fa
    } yield f(x)

given [A, M, C]: Applicative[[C] =>> HandleM[A, M, C]] with
  def pure[C](a: C): HandleM[A, M, C] = HandleM(s => Continue(s, a))
  def ap[C, B](
      ff: HandleM[A, M, C => B]
  )(fa: HandleM[A, M, C]): HandleM[A, M, B] =
    for
      f_ <- ff
      v <- fa
    yield f_(v)

given [A, M, C]: Monad[[C] =>> HandleM[A, M, C]] with
  def pure[C](a: C): HandleM[A, M, C] = HandleM(s => Continue(s, a))
  def flatMap[C, B](
      fa: HandleM[A, M, C]
  )(f: C => HandleM[A, M, B]): HandleM[A, M, B] =
    HandleM(state =>
      fa.runHandleM(state) match
        case Continue(state_, v) => f(v).runHandleM(state_)
        case ModifyBehavior(b)   => ModifyBehavior(b)
        case AwaitWindow(w, msg, state_, next) =>
          AwaitWindow(w, msg, state_, x => next(x) >>= f)
    )

  // Tail call recursive not possible
  def tailRecM[C, B](
      a: C
  )(f: C => HandleM[A, M, Either[C, B]]): HandleM[A, M, B] =
    for
      x <- f(a)
      b <- x match
        case Right(b) => pure(b)
        case Left(c)  => tailRecM(c)(f)
    yield b

object HandleM:
  /** A shortcut for point()
    *
    * @return
    */
  def point[A, M, C]: C => HandleM[A, M, C] =
    x => summon[Monad[[C] =>> HandleM[A, M, C]]].point(x)

  /** Handle with context
    *
    * Note that there is no getContext beacuse context may change while
    * awaiting. getContext may cause the user to hold an invalid reference.
    *
    * @return
    */
  def liftContextIO[A, M]
      : (ActorContext[MsgT[A, M]] => Unit) => HandleM[A, M, Unit] =
    f => HandleM(s => Continue(s, f(s._3)))

  /** Get current message
    *
    * @return
    */
  def getMsg[A, M]: HandleM[A, M, M] =
    HandleM(s => Continue(s, s._1))

  /** Modify current windowed CRDT.
    *
    * This operation must be monotonic and it is user's resoponsiblity to ensure
    * that.
    *
    * @return
    */
  def modifyCRDT[A, M]: (A => A) => HandleM[A, M, Unit] =
    f =>
      HandleM(s =>
        Continue(s.copy(_2 = s._2.copy(wcrdt = s._2.wcrdt.update(f))), ())
      )

  /** Go to next window
    *
    * Automatically broadcast update to all other actors
    *
    * @return
    */
  def nextWindow[A: CRDT, M]: HandleM[A, M, Unit] =
    HandleM(s =>
      // Broadcast update
      val (msg, state, ctx) = s
      val wcrdt = state.wcrdt.nextWindow(state.currentStream)
      state.actorRefs.foreach((id, ref) =>
        if id != state.actorId then ref ! Merge(wcrdt)
      )
      s._3.log
        .debug(s"Actor ${s._2.actorId} completed window#${s._2.wcrdt.window.v}")
      Continue(
        s.copy(_2 = s._2.copy(wcrdt = wcrdt)),
        ()
      )
    )

  /** Read current window number
    *
    * @return
    */
  def currentWindow[A, M]: HandleM[A, M, WindowID] =
    HandleM(s => Continue(s, s._2.wcrdt.window.v))

  /** Lift an IO operation into current context.
    *
    * Although it is possible to use mutable state, it is not recommended.
    * Mutable state can not be recovered while doing fault recovery.
    *
    * @return
    */
  def liftIO[A, M, B]: (=> B) => HandleM[A, M, B] =
    f => HandleM(s => Continue(s, f))

  /** Await for a window's value.
    *
    * This opeartion will block until the value is ready. Or return immediately
    * if it is already there. Blocking is required for confluent property of
    * windowed CRDT.
    *
    * @return
    */
  def await[A, M]: Int => HandleM[A, M, A] =
    w =>
      HandleM(s =>
        val (msg, state, ctx) = s
        if state.wcrdt.window.v <= w then
          ctx.log.error(
            s"[Deadlock detected] Actor ${state.actorId} " +
              s"is waiting for window $w while itself is " +
              s"currently at window ${state.wcrdt.window}"
          )
        state.wcrdt.query(w)(state.actorIdSet) match
          case Some(v) => Continue(s, v)
          case None =>
            ctx.log.debug(
              s"Actor ${s._2.actorId} stopped, waiting for window#$w"
            )
            AwaitWindow(
              w,
              msg,
              s,
              x => summon[Monad[[C] =>> HandleM[A, M, C]]].point(x)
            )
      )

  /** Update state when a new message arrives
    *
    *   - Update Stream in ActorState
    *   - Send a message to itself of the next msg in the stream
    *
    * @return
    */
  private[Types] def prepareHandleNewMsg[A, M]
      : M => Stream[M] => HandleM[A, M, Unit] =
    msg =>
      stream =>
        HandleM((_, state, ctx) =>
          stream.take(1).toList match
            case x :: _ =>
              ctx.log.debug(
                s"Actor ${state.actorId} queued a new message to mailbox: $x"
              )
              state.actorRefs(state.actorId) ! Process(x, stream.tail)
              Continue((msg, state.copy(currentStream = stream.tail), ctx), ())
            case _ =>
              ctx.log.debug(
                s"Actor ${state.actorId} has no more messages in the stream."
              )
              Continue((msg, state, ctx), ())
        )
