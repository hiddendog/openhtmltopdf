package org.xhtmlrenderer.swing;

import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xhtmlrenderer.event.DocumentListener;
import org.xhtmlrenderer.extend.NamespaceHandler;
import org.xhtmlrenderer.extend.UserInterface;
import org.xhtmlrenderer.layout.Boxing;
import org.xhtmlrenderer.layout.Layer;
import org.xhtmlrenderer.layout.LayoutContext;
import org.xhtmlrenderer.layout.PageInfo;
import org.xhtmlrenderer.layout.SharedContext;
import org.xhtmlrenderer.layout.content.DomToplevelNode;
import org.xhtmlrenderer.render.Box;
import org.xhtmlrenderer.render.ReflowEvent;
import org.xhtmlrenderer.render.RenderQueue;
import org.xhtmlrenderer.render.RenderingContext;
import org.xhtmlrenderer.util.Configuration;
import org.xhtmlrenderer.util.Uu;
import org.xhtmlrenderer.util.XRLog;


public class RootPanel extends JPanel implements ComponentListener, UserInterface {
    protected Dimension intrinsic_size;

    private boolean useThreads;

    public RootPanel(boolean useThreads) {
        this.useThreads = useThreads;
    }

    public RootPanel() {
        this(Configuration.isTrue("xr.use.threads", true));
    }

    public Dimension getIntrinsicSize() {
        return intrinsic_size;
    }

    protected Map documentListeners;

    public SharedContext getSharedContext() {
        return sharedContext;
    }

    protected SharedContext sharedContext;

    //TODO: layout_context should not be stored!
    protected volatile LayoutContext layout_context;

    private Box rootBox = null;

    private Thread layoutThread;
    private Thread renderThread;

    private PageInfo pageInfo = null;

    public void setDocument(Document doc, String url, NamespaceHandler nsh) {
        resetScrollPosition();
        setRootBox(null);
        this.doc = doc;

        //have to do this first
        if (Configuration.isTrue("xr.cache.stylesheets", true)) {
            getSharedContext().getCss().flushStyleSheets();
        } else {
            getSharedContext().getCss().flushAllStyleSheets();
        }
        getSharedContext().setBaseURL(url);
        getSharedContext().setNamespaceHandler(nsh);
        getSharedContext().setMedia(pageInfo == null ? "screen" : "print");
        getSharedContext().getCss().setDocumentContext(getSharedContext(), getSharedContext().getNamespaceHandler(), doc, this);

        if (isUseThreads()) {
            queue.dispatchLayoutEvent(new ReflowEvent(ReflowEvent.DOCUMENT_SET));
        } else {
            repaint();
        }
    }

    protected JScrollPane enclosingScrollPane;
    public void resetScrollPosition() {
        if (this.enclosingScrollPane != null) {
            this.enclosingScrollPane.getVerticalScrollBar().setValue(0);
        }
    }

    /**
     * The method is invoked by {@link #addNotify} and {@link #removeNotify} to
     * ensure that any enclosing {@link JScrollPane} works correctly with this
     * panel. This method can be safely invoked with a <tt>null</tt> scrollPane.
     *
     * @param scrollPane the enclosing {@link JScrollPane} or <tt>null</tt> if
     *                   the panel is no longer enclosed in a {@link JScrollPane}.
     */
    protected void setEnclosingScrollPane(JScrollPane scrollPane) {
        // if a scrollpane is already installed we remove it.
        if (enclosingScrollPane != null) {
            enclosingScrollPane.removeComponentListener(this);
        }

        enclosingScrollPane = scrollPane;

        if (enclosingScrollPane != null) {
            Uu.p("added root panel as a component listener to the scroll pane");
            enclosingScrollPane.addComponentListener(this);
            default_scroll_mode = enclosingScrollPane.getViewport().getScrollMode();
        }
    }

    private int default_scroll_mode = -1;

    /**
     * Gets the fixedRectangle attribute of the BasicPanel object
     *
     * @return The fixedRectangle value
     */
    public Rectangle getFixedRectangle() {
        if (enclosingScrollPane != null) {
            return enclosingScrollPane.getViewportBorderBounds();
        } else {
            Dimension dim = getSize();
            return new Rectangle(0, 0, dim.width, dim.height);
        }
    }

    /**
     * Overrides the default implementation to test for and configure any {@link
     * JScrollPane} parent.
     */
    public void addNotify() {
        super.addNotify();
        XRLog.general(Level.FINE, "add notify called");
        Container p = getParent();
        if (p instanceof JViewport) {
            Container vp = p.getParent();
            if (vp instanceof JScrollPane) {
                setEnclosingScrollPane((JScrollPane) vp);
            }
        }
    }

    /**
     * Overrides the default implementation unconfigure any {@link JScrollPane}
     * parent.
     */
    public void removeNotify() {
        super.removeNotify();
        setEnclosingScrollPane(null);
    }

    protected Document doc = null;

    /**
     * The queue to handle painting and layout events
     */
    RenderQueue queue;

    protected void init() {


        documentListeners = new HashMap();
        setBackground(Color.white);
        super.setLayout(null);

        if (isUseThreads()) {
            queue = new RenderQueue();

            layoutThread = new Thread(new LayoutLoop(this), "FlyingSaucer-Layout");
            renderThread = new Thread(new RenderLoop(this), "FlyingSaucer-Render");

            layoutThread.start();
            renderThread.start();
        }
    }

    public synchronized void shutdown() {
        try {
            if (layoutThread != null) {
                layoutThread.interrupt();
                layoutThread.join();
                layoutThread = null;
            }

            if (renderThread != null) {
                renderThread.interrupt();
                renderThread.join();
                renderThread = null;
            }

        } catch (InterruptedException e) {
            // ignore
        }
    }

    int rendered_width = 0;

    protected int getRenderWidth() {
        return rendered_width;
    }

    protected void setRenderWidth(int renderWidth) {
        this.rendered_width = renderWidth;
    }


    boolean layoutInProgress = false;


    public ReflowEvent last_event = null;

    protected RenderingContext newRenderingContext(PageInfo pageInfo, Graphics2D g) {
        XRLog.layout(Level.FINEST, "new context begin");

        getSharedContext().setCanvas(this);

        Rectangle extents;

        extents = getBaseExtents(pageInfo);


        //Uu.p("newContext() = extents = " + extents);
        getSharedContext().setMaxWidth(0);
        //getSharedContext().setMaxHeight(0);
        XRLog.layout(Level.FINEST, "new context end");
        //Uu.p("new context with extents: " + extents);
        
        RenderingContext result = getSharedContext().newRenderingContextInstance(extents);
        result.setGraphics(g);
        result.setPrint(pageInfo != null);
        result.setInteractive(pageInfo == null);

        return result;
    }

    protected LayoutContext newLayoutContext(PageInfo pageInfo, Graphics2D g) {
        XRLog.layout(Level.FINEST, "new context begin");

        getSharedContext().setCanvas(this);

        Rectangle extents;

        extents = getBaseExtents(pageInfo);

        //Uu.p("newContext() = extents = " + extents);
        getSharedContext().setMaxWidth(0);
        //getSharedContext().setMaxHeight(0);
        XRLog.layout(Level.FINEST, "new context end");
        //Uu.p("new context with extents: " + extents);

        LayoutContext result = getSharedContext().newLayoutContextInstance(extents);
        result.setGraphics(g.getDeviceConfiguration().createCompatibleImage(1, 1).createGraphics());

        result.setPrint(pageInfo != null);
        result.setInteractive(pageInfo == null);

        return result;
    }

    public Rectangle getBaseExtents(PageInfo pageInfo) {
        Rectangle extents;
        if (pageInfo != null) {
            extents = new Rectangle(0, 0,
                    (int) pageInfo.getContentWidth(), (int) pageInfo.getContentHeight());
        } else if (enclosingScrollPane != null) {
            Rectangle bnds = enclosingScrollPane.getViewportBorderBounds();
            extents = new Rectangle(0, 0, bnds.width, bnds.height);
            //Uu.p("bnds = " + bnds);
        } else {
            extents = new Rectangle(getWidth(), getHeight());//200, 200 ) );
        }
        return extents;
    }

    public void doActualLayout(Graphics g) {
        //Uu.p("doActualLayout called");
        this.removeAll();
        if (g == null) {
            return;
        }
        if (doc == null) {
            return;
        }
        
        LayoutContext c = newLayoutContext(pageInfo, (Graphics2D) g);
        synchronized (this) {
            if (this.layout_context != null) this.layout_context.stopRendering();
            this.layout_context = c;
        }
        c.setRenderQueue(queue);
        setRenderWidth((int) c.getExtents().getWidth());
        getSharedContext().getTextRenderer().setupGraphics(c.getGraphics());
        
        Box root = Boxing.preLayout(c, new DomToplevelNode(doc));
        setRootBox(root);
        
        Boxing.realLayout(c, root, new DomToplevelNode(doc));
        
        if (!c.isStylesAllPopped()) {
            XRLog.layout(Level.SEVERE, "mismatch in style popping and pushing");
        }

        if (c.shouldStop()) {//interrupted layout
            return;
        }
// if there is a fixed child then we need to set opaque to false
// so that the entire viewport will be repainted. this is slower
// but that's the hit you get from using fixed layout
        if (root.getLayer().containsFixedContent()) {
            super.setOpaque(false);
        } else {
            super.setOpaque(true);
        }
        
        XRLog.layout(Level.FINEST, "after layout: " + root);
        
        Point maxOffset = root.getLayer().getMaxOffset();
        root.getLayer().setPositionsFinalized(true);
        intrinsic_size = new Dimension(maxOffset.x, maxOffset.y);
        
        //Uu.p("intrinsic size = " + intrinsic_size);
        if (intrinsic_size.width != this.getWidth()) {
            //Uu.p("intrisic and this widths don't match: " + this.getSize() + " "  + intrinsic_size);
            this.setPreferredSize(new Dimension(intrinsic_size.width, this.getHeight()));
            //this.setPreferredSize(intrinsic_size);
            this.revalidate();
        }

        // if doc is shorter than viewport
        // then stretch canvas to fill viewport exactly
        // then adjust the body element accordingly
        if (enclosingScrollPane != null) {
            if (intrinsic_size.height < enclosingScrollPane.getViewport().getHeight()) {
                //Uu.p("int height is less than viewport height");
                // XXX Not threadsafe
                if (enclosingScrollPane.getViewport().getHeight() != this.getHeight()) {
                    this.setPreferredSize(new Dimension(getWidth(), enclosingScrollPane.getViewport().getHeight()));
                    this.revalidate();
                }
                //Uu.p("need to do the body hack");
                if (root != null) {
                    root.height = enclosingScrollPane.getViewport().getHeight();
                    bodyExpandHack(root, root.height);
                    intrinsic_size.height = root.height;
                }
            } else {  // if doc is taller than viewport
                if (this.getHeight() != intrinsic_size.height) {
                    this.setPreferredSize(new Dimension(getWidth(), intrinsic_size.height));
                    this.revalidate();
                }

            }
            
            
            // turn on simple scrolling mode if there's any fixed elements
            if (root.getLayer().containsFixedContent()) {
                // Uu.p("is fixed");
                enclosingScrollPane.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            } else {
                // Uu.p("is not fixed");
                enclosingScrollPane.getViewport().setScrollMode(default_scroll_mode);
            }
        } else {
            setPreferredSize(intrinsic_size);
            revalidate();
        }


        if (isUseThreads()) {
            queue.dispatchRepaintEvent(new ReflowEvent(ReflowEvent.LAYOUT_COMPLETE));
        }
        this.fireDocumentLoaded();
    }

    private static void bodyExpandHack(Box root, int view_height) {
        for (int i = 0; i < root.getChildCount(); i++) {
            // set the html box to the max
            Box html = root.getChild(i);
            if (html.element != null && html.element.getNodeName().equals("html")) {
                html.height = view_height;
                // set the body box to the max
                for (int j = 0; j < html.getChildCount(); j++) {
                    Box body = html.getChild(j);
                    if (body.element != null && body.element.getNodeName().equals("body")) {
                        body.height = view_height;
                    }
                }
            }
        }
    }

    protected void fireDocumentLoaded() {
        Iterator it = this.documentListeners.keySet().iterator();
        while (it.hasNext()) {
            DocumentListener list = (DocumentListener) it.next();
            list.documentLoaded();
        }
    }


    /*
    * ========= UserInterface implementation ===============
    */
    public Element hovered_element = null;

    public Element active_element = null;

    public Element focus_element = null;

    public boolean isHover(org.w3c.dom.Element e) {
        if (e == hovered_element) {
            return true;
        }
        return false;
    }

    public boolean isActive(org.w3c.dom.Element e) {
        if (e == active_element) {
            return true;
        }
        return false;
    }

    public boolean isFocus(org.w3c.dom.Element e) {
        if (e == focus_element) {
            return true;
        }
        return false;
    }

    public void componentHidden(ComponentEvent e) {
    }

    public void componentMoved(ComponentEvent e) {
    }

    public void componentResized(ComponentEvent e) {
        Uu.p("componentResized() " + this.getSize());
        Uu.p("viewport = " + enclosingScrollPane.getViewport().getSize());
        if (doc != null) {
            if (isUseThreads()) {
                queue.dispatchLayoutEvent(new ReflowEvent(ReflowEvent.CANVAS_RESIZED,
                        enclosingScrollPane.getViewport().getSize()));
            } else {
                setRootBox(null);
            }
        }
    }

    public void componentShown(ComponentEvent e) {
    }

    public double getLayoutWidth() {
        if (enclosingScrollPane != null) {
            return enclosingScrollPane.getViewportBorderBounds().width;
        } else {
            return getSize().width;
        }
    }

    public PageInfo getPageInfo() {
        return pageInfo;
    }

    public void setPageInfo(PageInfo pageInfo) {
        this.pageInfo = pageInfo;
    }

    public boolean isPrintView() {
        return this.pageInfo != null;
    }

    public boolean isUseThreads() {
        return useThreads;
    }

    public void setUseThreads(boolean useThreads) {
        this.useThreads = useThreads;
    }
    
    public synchronized Box getRootBox() {
        return rootBox;
    }
    
    public synchronized void setRootBox(Box rootBox) {
        this.rootBox = rootBox;
    }

    public synchronized Layer getRootLayer() {
        return getRootBox() == null ? null : getRootBox().getLayer();
    }
}