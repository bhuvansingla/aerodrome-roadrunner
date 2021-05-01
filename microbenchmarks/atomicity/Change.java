package atomicity;

class Change {
  static int x = 4, y = 5;
  // Used to implement a busy wait.
  static int z1 = -1, z2 = -1;
  
  //Swap the value of x and y concurrently
  public static void main(String args[]) {
    (new Thread(new ChangeA())).start();
    (new Thread(new ChangeB())).start();
  }
}

class ChangeA implements Runnable {
  public void run() {
    Change.z1 = Change.x;
    while(Change.z2 == -1)
      System.out.println("A is waiting");
    Change.x = Change.z2;
    System.out.println("x:"+Change.x);
  }
}

class ChangeB implements Runnable {
  public void run() {
    Change.z2 = Change.y;
    while(Change.z1 == -1)
      System.out.println("B is waiting");
    Change.y = Change.z1;
    System.out.println("y:"+Change.y);
  }
}
