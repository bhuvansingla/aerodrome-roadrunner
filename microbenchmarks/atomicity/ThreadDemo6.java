package atomicity;

/** This microbenchmark has one atomicity violation involving four threads:
 * 1, 2, 3 and 4, on the field obj.a.
 * */
public class ThreadDemo6 {

  public static void main(String args[]) {
    System.out.println("ThreadDemo6 started");
    try {
      new NewThread6(1); // create a new thread
      Thread.sleep(500);
      new NewThread6(2); // create a second thread
      Thread.sleep(500);
      new NewThread6(3); // create a third thread
      Thread.sleep(500); 
      new NewThread6(4); // create a fourth thread
      Thread.sleep(5000);
    } catch(Exception e) {
      e.printStackTrace();
    }
  }
  
}
