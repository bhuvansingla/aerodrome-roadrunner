package atomicity;

public class MyObject1 {
  public int a;
  public double b;
  public char c;
  
  public MyObject1() {
    a = 0;
    b = 3.2;
    c = 'a';
  }
  
  public void print() {
    System.out.println("a:" + a);
    System.out.println("b:" + b);
    System.out.println("c:" + 'c');
    System.out.println();
  }
}
