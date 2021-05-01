package atomicity;

/** This microbenchmark has an atomicity violation involving two threads:
 * 0 and 1, on static obj1 only if main() is considered to be atomic.
 * */
class ThreadDemo2 {
  
  public static void main(String args[]) {
    try {
      new NewThread2("firstchild"); // create a new thread
      Thread.sleep(500);
      new NewThread2("secondchild"); // create a second thread
    } catch(InterruptedException e) {
      e.printStackTrace();
    }
   }
  
}
