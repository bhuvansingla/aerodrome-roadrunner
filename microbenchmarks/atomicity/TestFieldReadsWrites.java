/**
 * @author fathi
 * 
 */

package atomicity;

// SB: Taken from ROCTET
public class TestFieldReadsWrites {
  
  private static int MAX_ITERATION = 5;

  private boolean booleanValue = true;
  private byte byteValue = 60;
  private short shortValue= 100;

 private TestFieldReadsWrites() {
   
 }
  /**
   * @param args
   */
  public static void main(String[] args) {

    TestFieldReadsWrites instance = new TestFieldReadsWrites();
    
    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
    System.out.println(" SHORT");
    System.out.println(instance.testShort());
    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
    System.out.println(" BOOLEAN");
    System.out.println(instance.testBoolean());
    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
    System.out.println(" BYTE");
    System.out.println(instance.testByte());
    System.out.println("%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
  }

  private boolean testBoolean() {
    for (int i = 0; i < MAX_ITERATION; i++) {
      booleanValue = !booleanValue;

    }
    return booleanValue;
  }

  private byte testByte() {
    for (int i = 0; i < MAX_ITERATION; i++) {
      byteValue++;

    }
    return byteValue;
  }

  private short testShort() {
    for (int i = 0; i < MAX_ITERATION; i++) {
      shortValue++;

    }
    return shortValue;
  }
}
