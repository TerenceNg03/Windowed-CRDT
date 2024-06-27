package Types

enum CRDTMsg[+A]:
    case Read[A](f: A => Unit) extends CRDTMsg[A]
    case Update[A](f: A => A) extends CRDTMsg[A]