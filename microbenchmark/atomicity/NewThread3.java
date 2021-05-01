package atomicity;

class NewThread3 implements Runnable {

  Thread t;
  static MyObject4 obj = new MyObject4(10);
  int index;

  NewThread3(String newname, int i) {
    // Create a new thread
    if (i == 1) {
      index = 1;
      t = new Thread(this, "First Thread");
    } else {
      index = 2;
      t = new Thread(this, "Second Thread");
    }
    t.start(); // Start the thread
  }
 
  // This is the entry point for the threads.
  public void run() {
    try {       
      for(int i=0; i < 2; i++) {
        synchronized(this) {
          if (index == 1) {
            obj.setInt(index);
            Thread.sleep(500);
            obj.getInt();
          }
          else {
            Thread.sleep(200);
            obj.setInt(index);
          }          
        }
      }
    } catch (InterruptedException e) {
    }
 }
 
}
