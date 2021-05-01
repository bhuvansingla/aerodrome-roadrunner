


/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2005
 */

import harness.Callback;

import java.lang.reflect.Method;

public class MMTkCallback extends Callback {
  private final String callbackClass = "org.mmtk.plan.Plan";
    
  private Method beginMethod, endMethod;
  private boolean found = false;
    
  /**
   * Locate the class where the harness resides in various versions
   * of JikesRVM, and then retain Method references to the correct one.
   */
  public MMTkCallback() {
    try {
      Class<?> harnessClass = Class.forName(callbackClass);
      beginMethod = harnessClass.getMethod("harnessBegin", (Class[])null);
      endMethod = harnessClass.getMethod("harnessEnd", (Class[])null); 
      found = true;
    } catch (ClassNotFoundException c) {
      System.err.println("Could not locate "+callbackClass);
    } catch (SecurityException e) {
      System.err.println("harness method of "+callbackClass+" is not accessible");
    } catch (NoSuchMethodException e) {
      System.err.println("harness method of "+callbackClass+" not found");
    }
    if (!found)
      System.err.println("WARNING: MMTk harness not found.");
  }
  
  @Override
  public void start() {
    super.start();
    if (!found) return;
    try {
      beginMethod.invoke(null, (Object[])null);
    } catch (Exception e) {
      throw new RuntimeException("Error running MMTk harnessBegin",e);
    }
  }
  
  @Override
  public void stop() {
    if (found) {
      try {
        endMethod.invoke(null, (Object[])null);
      } catch (Exception e) {
        throw new RuntimeException("Error running MMTk harnessEnd",e);
      }
    }
    super.stop();
  }
}
  
