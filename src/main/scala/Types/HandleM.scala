package Types

import Instances.ProcId
import Instances.WindowId
import Types.Internal.*
import cats.*
import cats.syntax.all.*
import org.apache.pekko.actor.typed.scaladsl.ActorContext

private[Types] sealed trait HandleResult[A, M, S, C]
private[Types] case class Continue[A, M, S, C](
    state: HandleState[A, M, S],
    v: C
) extends HandleResult[A, M, S, C]
private[Types] case class AwaitWindow[A, M, S, C](
    w: Int,
    msg: M,
    stream: S,
    state: HandleState[A, M, S],
    next: A => HandleM[A, M, S, C]
) extends HandleResult[A, M, S, C]

private[Types] case class HandleState[A, M, S](
    val msg: M,
    val stream: S,
    val state: ActorState[A, M, S],
    val ctx: ActorContext[MsgT[A, M, S]]
)

/** Handle Monad, represent computations within an actor.
  *
  * Mutable internal state is generally NOT compatiable with failure recovery
  * system. Use a LastWriteWin to warp the state and getLocalState instead.
  */
class HandleM[A, M, S, C] private[Types] (
    private[Types] val runHandleM: HandleState[A, M, S] => HandleResult[
      A,
      M,
      S,
      C
    ]
)

given [A, M, S, C]: Functor[[C] =>> HandleM[A, M, S, C]] with
  def map[C, B](fa: HandleM[A, M, S, C])(f: C => B): HandleM[A, M, S, B] =
    for {
      x <- fa
    } yield f(x)

given [A, M, S, C]: Applicative[[C] =>> HandleM[A, M, S, C]] with
  def pure[C](a: C): HandleM[A, M, S, C] = HandleM(s => Continue(s, a))
  def ap[C, B](
      ff: HandleM[A, M, S, C => B]
  )(fa: HandleM[A, M, S, C]): HandleM[A, M, S, B] =
    for
      f_ <- ff
      v <- fa
    yield f_(v)

given [A, M, S, C]: Monad[[C] =>> HandleM[A, M, S, C]] with
  def pure[C](a: C): HandleM[A, M, S, C] = HandleM(s => Continue(s, a))
  def flatMap[C, B](
      fa: HandleM[A, M, S, C]
  )(f: C => HandleM[A, M, S, B]): HandleM[A, M, S, B] =
    HandleM(state =>
      fa.runHandleM(state) match
        case Continue(state_, v) => f(v).runHandleM(state_)
        case AwaitWindow(w, msg, stream, state_, next) =>
          AwaitWindow(w, msg, stream, state_, x => next(x) >>= f)
    )

  // Tail call recursive not possible
  def tailRecM[C, B](
      a: C
  )(f: C => HandleM[A, M, S, Either[C, B]]): HandleM[A, M, S, B] =
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
  def pure[A, M, S, C]: C => HandleM[A, M, S, C] =
    x => summon[Monad[[C] =>> HandleM[A, M, S, C]]].point(x)

  /** Get current procId, some CRDT require it to update
    *
    * @return
    */
  def getProcId[A, M, S]: HandleM[A, M, S, ProcId] =
    HandleM(s => Continue(s, s.state.procId))

  /** Handle with context
    *
    * Note that there is no getContext beacuse context may change while
    * awaiting. getContext may cause the user to hold an invalid reference.
    *
    * @return
    */
  def liftContextIO[A, M, S]
      : (ActorContext[MsgT[A, M, S]] => Unit) => HandleM[A, M, S, Unit] =
    f => HandleM(s => Continue(s, f(s.ctx)))

  /** Get current message
    *
    * @return
    */
  def getMsg[A, M, S]: HandleM[A, M, S, M] =
    HandleM(s => Continue(s, s._1))

  /** Modify current windowed CRDT.
    *
    * This operation must be monotonic and it is user's resoponsiblity to ensure
    * that.
    *
    * @return
    */
  def modifyCRDT[A, M, S]: (A => A) => HandleM[A, M, S, Unit] =
    f =>
      HandleM(s =>
        Continue(
          s.copy(state = s.state.copy(wcrdt = s.state.wcrdt.update(f))),
          ()
        )
      )

  /** Return local modification of the CRDT.
    *
    * This call is not blocking.
    *
    * @return
    */
  def getLocalState[A, M, S]: HandleM[A, M, S, A] =
    HandleM(s => Continue(s, s.state.wcrdt.innerCRDT.v))

  /** Go to next window
    *
    * Automatically broadcast update to all other actors
    *
    * @return
    */
  def nextWindow[A: CRDT, M, S]: HandleM[A, M, S, Unit] =
    HandleM(hs =>
      // Broadcast update
      val HandleState(msg, stream, state, ctx) = hs
      val wcrdt =
        state.wcrdt.nextWindow(state.procId)(stream)
      state.actorRefs.foreach(ref =>
        ref ! Merge(state.nodeId, state.procId, wcrdt)
      )
      ctx.log
        .info(
          s"Replica ${state.procId} completed window#${state.wcrdt.window.v}"
        )
      Continue(
        hs.copy(state = state.copy(wcrdt = wcrdt)),
        ()
      )
    )

  /** Go to next window
    *
    * Automatically broadcast update to all other actors but messages have a delay to arrive
    *
    * @return
    */
  def nextWindowDelayed[A: CRDT, M, S](delay: Int): HandleM[A, M, S, Unit] =
    HandleM(hs =>
      // Broadcast update
      val HandleState(msg, stream, state, ctx) = hs
      val wcrdt =
        state.wcrdt.nextWindow(state.procId)(stream)
      state.actorRefs.foreach(ref =>
        new Thread(() =>
            Thread.sleep(delay)
            ref ! Merge(state.nodeId, state.procId, wcrdt)
          ).start()
      )
      ctx.log
        .info(
          s"Replica ${state.procId} completed window#${state.wcrdt.window.v}"
        )
      Continue(
        hs.copy(state = state.copy(wcrdt = wcrdt)),
        ()
      )
    )

  /** Read current window number
    *
    * @return
    */
  def currentWindow[A, M, S]: HandleM[A, M, S, WindowId] =
    HandleM(s => Continue(s, s.state.wcrdt.window.v))

  /** Lift an IO operation into current context.
    *
    * Although it is possible to use mutable state, it is not recommended.
    * Mutable state can not be recovered while doing fault recovery.
    *
    * @return
    */
  def liftIO[A, M, S, B]: (=> B) => HandleM[A, M, S, B] =
    f => HandleM(s => Continue(s, f))

  /** Await for a window's value.
    *
    * This opeartion will block until the value is ready. Or return immediately
    * if it is already there. Blocking is required for confluent property of
    * windowed CRDT.
    *
    * @return
    */
  def await[A, M, S]: Int => HandleM[A, M, S, A] =
    w =>
      assert(w >= 0)
      HandleM { case s @ HandleState(msg, stream, state, ctx) =>
        if state.wcrdt.window.v <= w then
          ctx.log.error(
            s"[Deadlock detected] Replica ${state.procId} " +
              s"is waiting for window $w while itself is " +
              s"currently at window ${state.wcrdt.window.v}"
          )
          state.mainRef ! FatalFailure(state.nodeId, "Dead lock encountered!")
          throw new RuntimeException("Deadlock")
        state.wcrdt.query(w)(state.actorIdSet) match
          case Some(v) => Continue(s, v)
          case None =>
            ctx.log.info(
              s"Replica ${state.procId} stopped, waiting for window#$w"
            )
            AwaitWindow(
              w,
              msg,
              stream,
              s,
              x => summon[Monad[[C] =>> HandleM[A, M, S, C]]].point(x)
            )
      }

  /** Throw an exception and crash the actor.
    *
    * @return
    */
  def error[A, M, S]: String => HandleM[A, M, S, Unit] =
    s =>
      HandleM { case HandleState(msg, stream, state, ctx) =>
        ctx.log.error(
          s"Node ${state.nodeId} (Replica ${state.procId}) encountered an error: $s"
        )
        throw new RuntimeException(s)
      }

  /** Like error, but will cause main to terminate the system
    *
    * @return
    */
  def fatal[A, M, S]: String => HandleM[A, M, S, Unit] =
    s =>
      HandleM { case HandleState(msg, stream, state, ctx) =>
        ctx.log.error(
          s"Node ${state.nodeId} (Replica ${state.procId}) encountered an FATAL error: $s"
        )
        state.mainRef ! FatalFailure(state.nodeId, s)
        throw new RuntimeException(s)
      }

  /** Update state when a new message arrives
    *
    *   - Update stream in ActorState
    *   - Send a message to itself of the next msg in the stream
    *
    * @return
    */
  private[Types] def prepareHandleNewMsg[A, M, S](using
      x: PersistStream[S, M]
  ): M => S => HandleM[A, M, S, Unit] =
    msg =>
      stream =>
        HandleM { case HandleState(_, _, state, ctx) =>
          stream.next match
            case (Some(x), ns) =>
              ctx.log.debug(
                s"Replica ${state.actorRefs} queued a new message to mailbox: $x"
              )
              ctx.self ! Process(x, ns)
              Continue(
                HandleState(
                  msg,
                  stream,
                  state,
                  ctx
                ),
                ()
              )
            case _ =>
              ctx.log.debug(
                s"Actor ${state.procId} has no more messages in the stream."
              )
              Continue(HandleState(msg, stream, state, ctx), ())
        }
