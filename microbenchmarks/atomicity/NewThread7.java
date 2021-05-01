package atomicity;

public class NewThread7 implements Runnable {
  int index;
  Thread t;
  static MyObject4 obj = new MyObject4(10);

  public NewThread7(int i) {
    // Create a new thread
    if (i == 1) {
      index = 1;
      t = new Thread(this, "First Thread");
    } else if (i == 2) {
      index = 2;
      t = new Thread(this, "Second Thread");
    } else {
      index = 3;
      t = new Thread(this, "Third Thread");
    }
    t.start(); // Start the thread
  }

  @Override
  public void run() {
    access();
  }

  public void access() {
    try {
      if (index == 1) {
        System.out.println("Thread 1 writing to obj for the first time");
        obj.a = 10;
        Thread.sleep(2000);
        obj.a = 40;
        System.out.println("Thread 1 writing to obj for the second time");
        Thread.sleep(6000);
      } else if (index == 2) {
        System.out.println("Thread 2 writing to obj for the first time");
        obj.a = 50;
        Thread.sleep(4000);
        // obj.a = 60;
        // System.out.println("Thread 2 writing to obj for the second time");
        // Thread.sleep(8000);
      } else if (index == 3) {
        System.out.println("Thread 3 writing to obj for the first time");
        obj.a = 100;
        Thread.sleep(4000);
      } else {
        // Do nothing
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}
