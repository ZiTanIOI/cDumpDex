/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: aidl D:/cDumpDex/app/src/main/aidl/com/zitan/cdumpdex/IFileService.aidl
 */
package com.zitan.cdumpdex;
public interface IFileService extends android.os.IInterface
{
  /** Default implementation for IFileService. */
  public static class Default implements com.zitan.cdumpdex.IFileService
  {
    // Destroy method defined by Shizuku server
    @Override public void destroy() throws android.os.RemoteException
    {
    }
    // Write file to specified path
    @Override public boolean writeFile(java.lang.String path, java.lang.String content) throws android.os.RemoteException
    {
      return false;
    }
    // Create directory
    @Override public boolean mkdir(java.lang.String path) throws android.os.RemoteException
    {
      return false;
    }
    // Check if file exists
    @Override public boolean exists(java.lang.String path) throws android.os.RemoteException
    {
      return false;
    }
    // Read file content
    @Override public java.lang.String readFile(java.lang.String path) throws android.os.RemoteException
    {
      return null;
    }
    // Execute shell command and return output
    @Override public java.lang.String executeCommand(java.lang.String command) throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.zitan.cdumpdex.IFileService
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.zitan.cdumpdex.IFileService interface,
     * generating a proxy if needed.
     */
    public static com.zitan.cdumpdex.IFileService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.zitan.cdumpdex.IFileService))) {
        return ((com.zitan.cdumpdex.IFileService)iin);
      }
      return new com.zitan.cdumpdex.IFileService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_destroy:
        {
          this.destroy();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_writeFile:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          boolean _result = this.writeFile(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_mkdir:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          boolean _result = this.mkdir(_arg0);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_exists:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          boolean _result = this.exists(_arg0);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_readFile:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _result = this.readFile(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_executeCommand:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _result = this.executeCommand(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.zitan.cdumpdex.IFileService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      // Destroy method defined by Shizuku server
      @Override public void destroy() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_destroy, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // Write file to specified path
      @Override public boolean writeFile(java.lang.String path, java.lang.String content) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(path);
          _data.writeString(content);
          boolean _status = mRemote.transact(Stub.TRANSACTION_writeFile, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // Create directory
      @Override public boolean mkdir(java.lang.String path) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(path);
          boolean _status = mRemote.transact(Stub.TRANSACTION_mkdir, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // Check if file exists
      @Override public boolean exists(java.lang.String path) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(path);
          boolean _status = mRemote.transact(Stub.TRANSACTION_exists, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // Read file content
      @Override public java.lang.String readFile(java.lang.String path) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(path);
          boolean _status = mRemote.transact(Stub.TRANSACTION_readFile, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // Execute shell command and return output
      @Override public java.lang.String executeCommand(java.lang.String command) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(command);
          boolean _status = mRemote.transact(Stub.TRANSACTION_executeCommand, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_destroy = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16777114);
    static final int TRANSACTION_writeFile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_mkdir = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_exists = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_readFile = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_executeCommand = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.zitan.cdumpdex.IFileService";
  // Destroy method defined by Shizuku server
  public void destroy() throws android.os.RemoteException;
  // Write file to specified path
  public boolean writeFile(java.lang.String path, java.lang.String content) throws android.os.RemoteException;
  // Create directory
  public boolean mkdir(java.lang.String path) throws android.os.RemoteException;
  // Check if file exists
  public boolean exists(java.lang.String path) throws android.os.RemoteException;
  // Read file content
  public java.lang.String readFile(java.lang.String path) throws android.os.RemoteException;
  // Execute shell command and return output
  public java.lang.String executeCommand(java.lang.String command) throws android.os.RemoteException;
}
