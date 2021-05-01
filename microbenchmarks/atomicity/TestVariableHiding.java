package atomicity;

public class TestVariableHiding {
  
  public static class A {
    int a;
    int b;
    int c;
    public A(int a, int b, int c) {
      this.a = a;
      this.b = b;
      this.c = c;
    }
  }
  
  public static class B extends A {
    int a;
    public B(int a, int b, int c) {
      super(a, b, c);
      this.a = 10;
    }
  }
  
  public static void main(String[] args) {
    A base = new A(0, 1, 2);
    System.out.println("A.a:" + base.a + "A.b:" + base.b + "A.c:" + base.c);
    B derived = new B(4, 5, 6);
    System.out.println("B.a:" + derived.a + "B.b:" + derived.b + "B.c:" + derived.c);
    A base2 = derived;
    System.out.println("B.a:" + base2.a + "B.b:" + base2.b + "B.c:" + base2.c);
  }

}
