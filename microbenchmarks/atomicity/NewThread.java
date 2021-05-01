package atomicity;

class NewThread implements Runnable {
    Thread t;
    static MyObject3 obj;
    String name;

    NewThread(String newname) {
      obj = new MyObject3(10);
      name = newname;
      // Create a new thread
      t = new Thread(this, "Demo Thread");
      System.out.println("Child thread: " + t);
      t.start(); // Start the thread
    }
   
    // This is the entry point for the second thread.
    public void run() {
      try {
         for(int i = 10; i > 0; i--) {
           System.out.println(name + " is writing");
           obj.set(i);
            
           // Let the thread sleep for a while.
           Thread.sleep(500);
      
           System.out.print(name + " is reading ");
           System.out.println(obj.get());
         }
       } 
      catch (InterruptedException e) {
        System.out.println("Child interrupted.");
      }
      System.out.println("Exiting child thread " + name);
   }
}
