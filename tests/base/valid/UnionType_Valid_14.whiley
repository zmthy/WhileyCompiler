import println from whiley.lang.System

define nlist as nat|[int]

nlist f(int x):
    if x <= 0:
        return 0
    else:
        return f(x-1)

void ::main(System.Console sys):
    x = f(2)    
    sys.out.println(Any.toString(x))
