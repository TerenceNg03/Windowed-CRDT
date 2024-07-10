import java.util.concurrent.Semaphore

class MVar[A](
    var v: Option[A],
    lock1: Semaphore,
    lock2: Semaphore
):
  def put(x: A): Unit =
    lock1.release(1)
    v = Some(x)
    lock2.release(1)

  def get(): A =
    lock1.acquire(1)
    lock2.acquire(1)
    val a = v match
      case Some(x) => x
      case None    => ???
    v = None
    a

object MVar:
  def newMVar[A]: MVar[A] =
    val lock1 = new Semaphore(1)
    val lock2 = new Semaphore(1)
    lock1.acquire(1)
    lock2.acquire(1)
    MVar(None, lock1, lock2)
