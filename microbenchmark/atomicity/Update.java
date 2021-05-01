package atomicity;

class Update extends Thread {
  static Account acc = new Account();
  int id;
  
  Update(int id) {
    this.id = id;
  }
  
  public static void main(String args[]) {
    new Update(1).start();
    try {
      Thread.sleep(2000);
    }
    catch(InterruptedException e) {
      e.printStackTrace();
    }
    new Update(2).start();
  }
  
  public void run() {
    if(id == 1) {
      acc.update(123, id);
    }
    else {
      acc.update(300, id);
    }
  }
}
