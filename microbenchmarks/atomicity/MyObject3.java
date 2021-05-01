package atomicity;

public class MyObject3 {
  static int a;

  public MyObject3(int k)
  {
    a = k;
  }
  public synchronized void set(int d)
  {
    a = d;
  }
  public synchronized int get()
  {
    return a;
  }
}
