package Types

trait Lattice[A]:
    infix def \/(a: A)(b: A): A
    infix def /\(a: A)(b: A): A


trait BoundedJoinSemiLattice[A] extends Lattice[A]:
    val bottom:A

trait BoundedMeetSemiLattice[A] extends Lattice[A]:
    val top:A