package atomicity;

/**
 * This example is from the following paper: A Decision Procedure for Detecting 
 * Atomicity Violations for Communicating Processes with Locks 
 * 
 * @author biswass
 *
 */

public class Client {
  // @atomic
  public synchronized Object get(Stack s, int id) {
    if(!s.empty()) {
      if(id == 1) {
        try {
          Thread.sleep(5000);
        }
        catch(InterruptedException e) {
          e.printStackTrace();
        }
      }
      Object o = s.pop();
      if(id == 2) {
        try {
          Thread.sleep(15000);
        }
        catch(InterruptedException e) {
          e.printStackTrace();
        }
      }
      return o;
    }
    else {
      return null;
    }
  }
  public static Client makeClient() {
    return new Client();
  }
  public static void main(String[] args) {
    final Stack stack = Stack.makeStack();
    stack.push(new Integer(1));
    stack.push(new Integer(2));
    stack.push(new Integer(3));
    final Client client1 = makeClient();
    final Client client2 = makeClient();
    new Thread("1") { 
      { start(); }
      public void run() {
        client1.get(stack, 1); 
      } 
    };
    try {
      Thread.sleep(2000);
    }
    catch(InterruptedException e) {
      e.printStackTrace();
    }
    new Thread("2") {
      { start(); }
      public void run() {
        client2.get(stack, 2);
      }
    };
    System.out.println("hi");
  }
}
