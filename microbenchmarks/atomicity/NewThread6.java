package atomicity;

public class NewThread6 implements Runnable {
  int index;
  Thread t;
  static MyObject4 obj = new MyObject4(10);
  int bb;
  int cc;
  int dd;
  static final int[] VALUES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
  
  public NewThread6(int i) {
    // Create a new thread
    if (i == 1) {
      index = 1;
      t = new Thread(this, "First Thread");
    } else if (i == 2) {
      index = 2;
      t = new Thread(this, "Second Thread");
    } else if (i == 3) {
      index = 3;
      t = new Thread(this, "Third Thread");
    } else {
      index = 4;
      t = new Thread(this, "Fourth Thread");
    }
    bb = 9;
    cc = 99;
    dd = 999;
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
        System.out.println("Value:" + VALUES[1]);
        Thread.sleep(10000);
        obj.a = 40;
        System.out.println("Thread 1 writing to obj for the second time");
        System.out.println("Value:" + VALUES[1]);
        Thread.sleep(2000);
      } else if(index == 2) {
        System.out.println("Thread 2 writing to obj for the first time");
        obj.a = 50;
        System.out.println("Value:" + VALUES[1]);
        Thread.sleep(5000);
//        obj.a = 60;
//        System.out.println("Thread 2 writing to obj for the second time");
        System.out.println("Value:" + VALUES[1]);
      } else if(index == 3) {
        System.out.println("Thread 3 writing to obj for the first time");
        obj.a = 100;
        System.out.println("Value:" + VALUES[1]);
      } else if(index == 4) {
        System.out.println("Thread 4 writing to obj for the first time");
        obj.a = 200;
        System.out.println("Value:" + VALUES[1]);
      } else {
        // Do nothing
      }
    } catch(Exception e) {
      e.printStackTrace();
    }
  }

}