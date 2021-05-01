package atomicity;

public class Synchronize {
  int a;
  int b;
  String name;
  
  public Synchronize(String str) {
    a = 0;
    b = 0;
    name = str;
  }
  
  public void get() {
    synchronized (this) {
      System.out.println(a + " " + b + " " + name); 
    }
  }
  
  public static void main(String args[]) {
    Synchronize obj = new Synchronize("first");
    obj.get();
  }

}
