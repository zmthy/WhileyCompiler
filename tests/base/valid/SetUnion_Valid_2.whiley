import * from whiley.lang.*

void ::main(System sys,[string] args):
     xs = {1,2,3,4}
     ys = xs ∪ {5,1}
     sys.out.println(Any.toString(xs))
     xs = xs ∪ {6}
     sys.out.println(Any.toString(xs))
     sys.out.println(Any.toString(ys))
