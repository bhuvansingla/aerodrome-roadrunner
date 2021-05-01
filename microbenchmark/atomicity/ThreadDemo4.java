package atomicity;

/** This microbenchmark has two atomicity violations involving three threads:
 * first, between 2 and 3, and
 * then between 1 and 2.
 * */
class ThreadDemo4 {

  public static void main(String args[]) {
    try {
      new NewThread4(1); // create a new thread
      Thread.sleep(500);
      new NewThread4(2); // create a second thread
      Thread.sleep(500);
      new NewThread4(3); // create a third thread
    } catch(Exception e) {
      e.printStackTrace();
    }
  }
  
}
