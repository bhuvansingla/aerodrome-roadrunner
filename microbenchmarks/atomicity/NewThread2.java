package atomicity;

class NewThread2 implements Runnable {
  Thread t;
  static MyObject2 obj1;
  static MyObject2 obj2;
  String name;

  NewThread2(String newname) {
    obj1 = new MyObject2();
    obj2 = new MyObject2();
    name = newname;
    // Create a new thread
    t = new Thread(this, name);
    t.start(); // Start the thread
  }
 
  // This is the entry point for the second thread.
  @Override
  public void run() {
    access();
  }

  public void access() {
    try {
      if (name.equalsIgnoreCase("firstchild")) {
        System.out.println("Thread 1 writing to obj1.a");
        obj1.a = 10;
        Thread.sleep(5000);
        System.out.println("Thread 1 writing to obj2.b");
        obj2.b = 20;
      } else if (name.equalsIgnoreCase("secondchild")) {
        System.out.println("Thread 2 writing to obj2.a");
        obj2.a = 25;
        Thread.sleep(2000);
        System.out.println("Thread 2 writing to obj1.a");
        obj1.a = 19;
        Thread.sleep(1000);
        System.out.println("Thread 2 writing to obj2.b");
        obj2.b = 11;
      } else {
        throw new RuntimeException();
      }
    } catch(Exception e) {
      e.printStackTrace();
    }
  }
}