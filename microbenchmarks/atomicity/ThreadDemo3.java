package atomicity;

class Sample {
  int a;
  int b;
  
  public Sample() {
    a = 0;
    b = 10;
  }
  
  public void get(int id) {
    synchronized (this) {
      if (id == 2) {
        b = 50;
        System.out.println("SECOND THREAD:b = " + b);
      }
    }
  }
  
  public void set(int i, int j, int id) {
    synchronized (this) {
     a = i;
     //b = j;
    }
    if (id == 1) {
      try {
        Thread.sleep(2000);
      }
      catch (InterruptedException e) {
        e.printStackTrace();
      }
      System.out.println("FIRST THREAD" + b);
    }
  }
  
}

class ThreadDemo3 extends Thread {
  static Sample sample = new Sample();
  String name;

  public ThreadDemo3(String str) {
    name = str;
  }
  
  public static void main(String args[]) {
    try {
      new ThreadDemo3("firstchild").start(); // create a new thread
      Thread.sleep(100);
      new ThreadDemo3("secondchild").start(); //create a second thread
    } catch(InterruptedException e) {
      e.printStackTrace();
    }
   }
  
  public void run() {
    if (this.name.equals("firstchild")) {
      sample.set(1, 2, 1);
    } else if (this.name.equals("secondchild")) {
      sample.get(2);
    } else {
      assert false;
    }
  }
  
}
