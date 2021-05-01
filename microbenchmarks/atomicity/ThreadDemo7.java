package atomicity;

public class ThreadDemo7 {

  public static void main(String args[]) {
    try {
      new NewThread7(1); // create a new thread
      Thread.sleep(500);
      new NewThread7(2); // create a second thread
      Thread.sleep(500);
      new NewThread7(3); // create a third thread
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}
