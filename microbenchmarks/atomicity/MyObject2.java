package atomicity;

class MyObject2 {
  
  public int a;
  public int b;
  public String name;
  public float f;
  
  MyObject2() {
    a = 0;
    b = 0;
    f = 0f;
  }
  
  MyObject2(float b) {
    f = b;
    a = 0;
    b = 0;
  }
  
  MyObject2(String n) {
    this.name = n;
    System.out.println("creating object " + n);
  }
  
//  protected void finalize() throws Throwable {
//    try {
//      System.out.println("finalizing "+this.name); 
//    }
//    catch(Exception e) {
//      e.printStackTrace();
//    }
//    finally {
//      super.finalize();
//    }
//  }
  
}
