import org.apache.pekko.actor.Actor
import Types.CRDT
import Types.CRDTMsg

class CRDTActor[A](var v: A) extends Actor:
  def receive =
    case CRDTMsg.Read[A](f)   => f(v)
    case CRDTMsg.Update[A](f) => v = f(v)
    case _                    => ???
