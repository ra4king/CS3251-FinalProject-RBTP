package edu.rbtp.impl;

/**
 * 
 * A Bindable is an object that can be bound to a full-duplex connection
 * When a Bindable is bound to a connection, the connection calls the bind method with a BindingInterface,
 * which contains the interface methods by which this Bindable and the connection communicate.
 * 
 * @author Roi Atalla
 */
public interface Bindable {
	boolean isBound();
	
	/**
	 * This method *MUST* set a packet-received Consumer before bind returns.
	 * 
	 * @param bindingInterface the BindingInterface between this Bindable and the connection it is bound to
	 */
	void bind(BindingInterface bindingInterface);
}
