package dev.codex.trime.smoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.PopupMenu;
import java.lang.reflect.*;

/** App-only geometry and menu checks; never accepts deletion or exports clipboard contents. */
@android.annotation.TargetApi(29)
public final class ClipboardRunner extends Instrumentation {
    interface Checked { void run() throws Exception; }
    private Object service, manager;
    private final Bundle result = new Bundle();
    private Class<?> cls(String n) throws Exception { return getTargetContext().getClassLoader().loadClass(n); }
    private Object field(Object o,String n) throws Exception {
        for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try { Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o); }catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(n);
    }
    private Object call(Object o,String n,Class<?>[] types,Object... args) throws Exception {
        for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try { Method m=c.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args); }catch(NoSuchMethodException ignored){}
        throw new NoSuchMethodException(n);
    }
    private Object get(Object o,String n) throws Exception { return call(o,n,new Class<?>[0]); }
    private void main(Checked code) throws Exception {
        Throwable[] error={null};runOnMainSync(()->{try{code.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new Exception(error[0]);
    }
    private void check(boolean p,String msg) throws Exception { if(!p)throw new Exception(msg); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        int code=Activity.RESULT_CANCELED;
        try {
            Activity activity=startActivitySync(new Intent().setClassName(getTargetContext(),"com.osfans.trime.ui.main.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(()->call(field(activity,"testInputPanel"),"show",new Class<?>[]{android.view.Window.class},activity.getWindow()));
            Field instance=cls("com.osfans.trime.ime.keyboard.KeyboardWindow").getDeclaredField("instance");instance.setAccessible(true);
            for(int i=0;i<60&&service==null;i++){
                main(()->{Object keyboard=instance.get(null);if(keyboard!=null)service=get(keyboard,"getService");});
                if(service==null)SystemClock.sleep(200);
            }
            check(service!=null,"Keyboard unavailable");waitForIdleSync();SystemClock.sleep(400);
            Object[] clipboard={null},title={null};
            main(()->{
                Object input=field(service,"inputView");manager=get(input,"getWindowManager");
                Object di=get(input,"getDi");
                clipboard[0]=cls("com.osfans.trime.ime.clipboard.ClipboardWindow").getConstructor(cls("org.kodein.di.DI"),int.class).newInstance(di,0);
                call(manager,"attachWindow",new Class<?>[]{cls("com.osfans.trime.ime.window.BoardWindow")},clipboard[0]);
                title[0]=get(field(clipboard[0],"clipboardLayout"),"getTitleUi");
            });
            waitForIdleSync();SystemClock.sleep(300);
            main(()->{
                ViewGroup root=(ViewGroup)get(title[0],"getRoot");View tabs=(View)get(title[0],"getTabScroll");View action=(View)get(title[0],"getActionsButton");
                check(root.getWidth()>0,"Header not laid out");
                check(tabs.getRight()<=action.getLeft(),"Groups overlap trailing action button");
                int visible=0;for(int i=0;i<root.getChildCount();i++)if(root.getChildAt(i).getVisibility()==View.VISIBLE)visible++;
                check(visible==2,"Expected only group strip and one action button");
                result.putString("header","groups constrained before one trailing action button");
                action.performClick();
                PopupMenu menu=(PopupMenu)field(clipboard[0],"actionsMenu");
                check(menu!=null&&menu.getMenu().size()==3,"Expected Search, Delete and temporary pin submenu");
                check(menu.getMenu().findItem(3).isCheckable(),"Missing temporary pin toggle");
                menu.getMenu().performIdentifierAction(3,0);
                check((Boolean)field(clipboard[0],"keepOpenForSession"),"Temporary pin not enabled");
                check(menu.getMenu().findItem(1)!=null&&menu.getMenu().findItem(2)!=null,"Missing menu action");
                menu.getMenu().performIdentifierAction(2,0);
            });
            waitForIdleSync();SystemClock.sleep(200);
            main(()->{
                View cancel=null;
                for(View root:WindowInspector.getGlobalWindowViews())if(root.hasWindowFocus())cancel=root.findViewById(android.R.id.button2);
                check(cancel!=null,"Delete confirmation missing");cancel.performClick();
                result.putString("delete","confirmation opened and canceled; no data deleted");
            });
            waitForIdleSync();SystemClock.sleep(150);
            main(()->{
                ((View)get(title[0],"getActionsButton")).performClick();
                PopupMenu menu=(PopupMenu)field(clipboard[0],"actionsMenu");menu.getMenu().performIdentifierAction(1,0);
                Object bridge=cls("com.osfans.trime.ime.clipboard.ClipboardSearchBridge").getField("INSTANCE").get(null);
                check((Boolean)get(bridge,"getActive"),"Search did not activate keyboard input bridge");
                check(field(clipboard[0],"actionsMenu")==null,"Detached clipboard left a popup open");
                check(activity.hasWindowFocus(),"Search stole editor focus");
                Object handler=field(bridge,"onText"), overlay=null;
                for(Field f:handler.getClass().getDeclaredFields()) {
                    f.setAccessible(true); Object value=f.get(handler);
                    if(cls("com.osfans.trime.ime.clipboard.ClipboardSearchOverlay").isInstance(value))overlay=value;
                }
                check(overlay!=null,"Missing search overlay capture");
                Object panel=field(activity,"testInputPanel"); android.widget.EditText host=(android.widget.EditText)field(panel,"input");
                String before=host.getText().toString();
                call(service,"commitText",new Class<?>[]{String.class},"测试🔎");
                check(field(overlay,"query").equals("测试🔎"),"Keyboard commit did not reach search query");
                call(service,"sendDownUpKeyEvent",new Class<?>[]{int.class,int.class},android.view.KeyEvent.KEYCODE_DEL,0);
                check(field(overlay,"query").equals("测试"),"Backspace did not remove a whole Unicode character");
                for(Method method:service.getClass().getDeclaredMethods())if(method.getName().startsWith("updateComposingText")) {
                    method.setAccessible(true);method.invoke(service,"search_preedit_fixture");break;
                }
                check(host.getText().toString().equals(before),"Search query or preedit leaked to host editor");
                result.putString("search_input","typed commits and Unicode backspace route to search; host editor unchanged");
                check((Boolean)get(bridge,"exit"),"Search back was not handled");
                check(!(Boolean)get(bridge,"getActive"),"Search bridge leaked after return");
                check(field(manager,"currentWindow")==clipboard[0],"Search back did not return to original clipboard page");
                check((Boolean)field(clipboard[0],"keepOpenForSession"),"Temporary pin was lost during search");
                result.putString("search","search returns to same clipboard instance; temporary pin survives");
            });
            code=Activity.RESULT_OK;result.putString("result","PASS");
        } catch(Throwable e){result.putString("result","FAIL: "+android.util.Log.getStackTraceString(e));}
        finally {
            try {main(()->{
                Object bridge=cls("com.osfans.trime.ime.clipboard.ClipboardSearchBridge").getField("INSTANCE").get(null);get(bridge,"reset");
                if(service!=null)get(service,"hideBottomOverlay");
            });}catch(Exception ignored){}
        }
        finish(code,result);
    }
}
