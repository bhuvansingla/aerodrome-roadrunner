package atomicity;

class test2 {
  public static void main(String[] args) {
    MyObject1 obj1 = new MyObject1();
    MyObject1 obj2 = new MyObject1(); 
    MyObject1 obj3 = new MyObject1();
    obj1.a = 4;
    obj1.b = 4.5;
    obj2.a = 5;
    obj2.b = obj2.a;
    obj2.c = obj1.c;
    MyObject1 obj4 = new MyObject1();
    try {
      Thread.sleep(1000);
      throw new RuntimeException();
    } catch(InterruptedException e) {
      e.printStackTrace();
    } catch(Exception e) {
      System.out.println("exception");
    }

    print(obj1);
  }

  public static int print(MyObject1 obj1) {
    return get(obj1);
  }
  
  private static int get(MyObject1 obj1) {
    System.out.println("hi");
    return obj1.a;
  }
}
