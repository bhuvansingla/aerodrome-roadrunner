package atomicity;

/** This microbenchmark has one atomicity violation involving three threads:
 * 1, 2, and 3, on the field obj.a.
 * */
public class ThreadDemo5 {
  
  public static void main(String args[]) {
    try {
      new NewThread5(1); // create a new thread
      Thread.sleep(500);
      new NewThread5(2); // create a second thread
      Thread.sleep(500);
      new NewThread5(3); // create a third thread
    }
    catch(Exception e) {
      e.printStackTrace();
    }
  }
  
}
