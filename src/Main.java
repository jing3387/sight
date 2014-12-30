import org.lwjgl.BufferUtils;
import org.lwjgl.Sys;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.*;

class Block {
    public double x, y, r;

    public Block(double x, double y, double r) {
        this.x = x;
        this.y = y;
        this.r = r;
    }
}

class Point {
    public double x = 0.0;
    public double y = 0.0;

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }
}

class Endpoint extends Point {
    public double angle = 0.0;
    public double dx = 0.0;
    public double dy = 0.0;
    public Segment segment = null;

    public Endpoint(double x, double y) {
        super(x, y);
    }

    public Endpoint(double x, double y, double angle) {
        super(x, y);
        this.angle = angle;
    }
}

class Segment {
    public Point p1;
    public Point p2;

    public Segment(Endpoint p1, Endpoint p2) {
        this.p1 = p1;
        this.p2 = p2;
        p1.segment = this;
        p2.segment = this;
    }
}

class Sight {
    private Integer vao;
    private Integer vbo;
    private Integer shader;
    private Integer position;
    private Integer lightPosition;
    private Point center;
    private ArrayList<Double> vertices;
    private ArrayList<Segment> segments;
    private TreeSet<Endpoint> endpoints;
    private ArrayList<Endpoint> rays;

    public Sight() {
        center = new Point(0.0, 0.0);
        vertices = new ArrayList<Double>();
        segments = new ArrayList<Segment>();
        endpoints = new TreeSet<Endpoint>(new PolarOrder());
        rays = new ArrayList<Endpoint>();
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        Integer vs = loadShader("vertex.glsl", GL_VERTEX_SHADER);
        Integer fs = loadShader("fragment.glsl", GL_FRAGMENT_SHADER);
        shader = glCreateProgram();
        glAttachShader(shader, vs);
        glAttachShader(shader, fs);
        glBindFragDataLocation(shader, 0, "color");
        glLinkProgram(shader);
        glValidateProgram(shader);
        position = glGetAttribLocation(shader, "position");
        lightPosition = glGetUniformLocation(shader, "u_lightPosition");
    }

    private Integer loadShader(String filename, int type) {
        StringBuilder shaderSource = new StringBuilder();
        int shaderId;
        try {
            InputStream is = getClass().getResourceAsStream(filename);
            InputStreamReader isr = new  InputStreamReader(is);
            BufferedReader reader = new BufferedReader(isr);
            String line;
            while ((line = reader.readLine()) != null)
                shaderSource.append(line).append("\n");
            reader.close();
        } catch (IOException e) {
            System.err.println("Could not read file.");
            e.printStackTrace();
            System.exit(-1);
        }
        shaderId = glCreateShader(type);
        glShaderSource(shaderId, shaderSource);
        glCompileShader(shaderId);
        int status = glGetShaderi(shaderId, GL_COMPILE_STATUS);
        if (status == GL_FALSE) {
            String buffer = glGetShaderInfoLog(shaderId, 1024);
            System.out.println(buffer);
            System.exit(-1);
        }
        return shaderId;
    }

    private void addSegment(double x1, double y1, double x2, double y2) {
        Endpoint p1 = new Endpoint(x1, y1);
        Endpoint p2 = new Endpoint(x2, y2);
        Segment segment = new Segment(p1, p2);
        segments.add(segment);
        endpoints.add(p1);
        endpoints.add(p2);
    }

    private void loadEdgeOfMap(double margin) {
        // Top-left to top-right.
        addSegment(-1.0+margin, 1.0-margin, 1.0-margin, 1.0-margin);
        // Top-right to bottom-right.
        addSegment(1.0-margin, 1.0-margin, 1.0-margin, -1.0+margin);
        // Bottom-right to bottom-left.
        addSegment(1.0-margin, -1.0+margin, -1.0+margin, -1.0+margin);
        // Bottom-left to top-left.
        addSegment(-1.0+margin, -1.0+margin, -1.0+margin, 1.0-margin);
    }

    public void loadMap(double margin, ArrayList<Block> blocks) {
        segments.clear();
        endpoints.clear();
        loadEdgeOfMap(margin);
        for (Block block : blocks) {
            double x = block.x;
            double y = block.y;
            double r = block.r;
            addSegment(x-r, y-r, x-r, y+r);
            addSegment(x-r, y+r, x+r, y+r);
            addSegment(x+r, y+r, x+r, y-r);
            addSegment(x+r, y-r, x-r, y-r);
        }
    }

    public void setLightLocation(double x, double y) {
        center.x = x;
        center.y = y;
        for (Endpoint p : endpoints) {
            p.angle = Math.atan2(p.y - y, p.x - x);
            p.dx = p.x - x;
            p.dy = p.y - y;
        }
        sweep();
    }

    public void moveLight(double x, double y) {
        setLightLocation(center.x + x, center.y + y);
    }

    public double cross(Point v, Point w) {
        return v.x*w.y - v.y*w.x;
    }

    public Point diff(Point v, Point w) {
        return new Point(v.x - w.x, v.y - w.y);
    }

    // First and third quadrant.
    private Point[] adjustments1 = {
            new Point(-0.00001, 0.00001),
            new Point(0.0, 0.0),
            new Point(0.00001, -0.00001)
    };

    // Second and fourth quadrant.
    private Point[] adjustments2 = {
            new Point(-0.00001, -0.00001),
            new Point(0.0, 0.0),
            new Point(0.00001, 0.00001)
    };

    private boolean firstOrThird(double angle) {
        return (0 <= angle && angle <= Math.PI/2) || (-Math.PI < angle && angle < -Math.PI/2);
    }

    // From http://ncase.me/sight-and-light/
    public void sweep() {
        vertices.clear();
        rays.clear();
        for (Endpoint point : endpoints) {
            Point[] adjustments = (firstOrThird(point.angle)) ? adjustments1 : adjustments2;
            for (Point adjustment : adjustments) {
                double min = Double.POSITIVE_INFINITY;
                // From http://stackoverflow.com/a/14318254
                Point p = center;
                Point r = new Point(point.dx + adjustment.x, point.dy + adjustment.y);
                for (Segment segment : segments) {
                    Point q = segment.p1;
                    Point s = new Point(segment.p2.x - segment.p1.x, segment.p2.y - segment.p1.y);
                    double rxs = cross(r, s);
                    if (rxs == 0)
                        continue;
                    Point q_p = diff(q, p);
                    double t = cross(q_p, s) / rxs;
                    double u = cross(q_p, r) / rxs;
                    if (0 <= t && 0 <= u && u <= 1)
                        min = (min > t) ? t : min;
                }
                Endpoint ray = new Endpoint(p.x + r.x * min, p.y + r.y * min, point.angle);
                rays.add(ray);
            }
        }
        System.out.println();
        Collections.sort(rays, new PolarOrder());
        rays.add(rays.get(0));
        for (int i = 0; i < rays.size(); i++) {
            vertices.add(center.x);
            vertices.add(center.y);
            Endpoint ray = rays.get(i);
            vertices.add(ray.x);
            vertices.add(ray.y);
            if (i+1 < rays.size()) {
                ray = rays.get(i + 1);
                vertices.add(ray.x);
                vertices.add(ray.y);
            }
        }
        DoubleBuffer verticesBuffer = BufferUtils.createDoubleBuffer(vertices.size());
        for (double v : vertices)
            verticesBuffer.put(v);
        verticesBuffer.flip();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, verticesBuffer, GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(position);
        glVertexAttribPointer(position, 2, GL_DOUBLE, false, 0, 0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glDisableVertexAttribArray(position);
        glBindVertexArray(0);
    }

    public void render() {
        glUseProgram(shader);
        glBindVertexArray(vao);
        glEnableVertexAttribArray(position);
        glUniform2f(lightPosition, (float) center.x, (float) center.y);
        glDrawArrays(GL_TRIANGLES, 0, vertices.size() / 2);
        glDisableVertexAttribArray(position);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    // From http://algs4.cs.princeton.edu/12oop/Point2D.java.html
    private class PolarOrder implements Comparator<Endpoint> {
        public int compare(Endpoint p1, Endpoint p2) {
            double dx1 = p1.x - center.x;
            double dy1 = p1.y - center.y;
            double dx2 = p2.x - center.x;
            double dy2 = p2.y - center.y;
            // Different quadrants.
            if (dy1 >= 0 && dy2 < 0) {
                return -1;
            } else if (dy2 >= 0 && dy1 < 0) {
                return +1;
            } else if (dy1 == 0 && dy2 == 0) {
                if (dx1 >= 0 && dx2 < 0)
                    return -1;
                else if (dx2 >= 0 && dx1 < 0)
                    return +1;
            }
            // Clockwise turn.
            double area2 = dx1*dy2 - dy1*dx2;
            if (area2 < 0)
                return +1;
            else if (area2 > 0)
                return -1;
            // Distance.
            double d1 = Math.sqrt(dx1*dx1 + dy1*dy1);
            double d2 = Math.sqrt(dx2*dx2 + dy2*dy2);
            if (d1 < d2)
                return -1;
            else if (d1 > d2)
                return 1;
            else
                return 0;

        }
    }

    public void cleanUp() {
        glDisableVertexAttribArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glDeleteBuffers(vbo);
        glBindVertexArray(0);
        glDeleteVertexArrays(vao);
    }
}

public class Main {
    private long window;
    private Sight sight;
    private GLFWErrorCallback errorCallback;
    private GLFWKeyCallback keyCallback;

    public void run() {
        System.out.println("LWJGL " + Sys.getVersion());
        try {
            init();
            loop();
            sight.cleanUp();
            glfwDestroyWindow(window);
            keyCallback.release();
        } finally {
            glfwTerminate();
            errorCallback.release();
        }
    }

    private void init() {
        glfwSetErrorCallback(errorCallback = errorCallbackPrint(System.err));
        if (glfwInit() != GL_TRUE)
            throw new IllegalStateException("Unable to initialize GLFW");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GL_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GL_FALSE);
        int WIDTH = 800;
        int HEIGHT = 600;
        window = glfwCreateWindow(WIDTH, HEIGHT, "Light", NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        glfwSetKeyCallback(window, keyCallback = new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scanCode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE)
                    glfwSetWindowShouldClose(window, GL_TRUE);
            }
        });
        ByteBuffer vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        glfwSetWindowPos(
                window,
                (GLFWvidmode.width(vidMode) - WIDTH) / 2,
                (GLFWvidmode.height(vidMode) - HEIGHT) / 2
        );
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);
    }

    private void loop() {
        DoubleBuffer mouseX = BufferUtils.createDoubleBuffer(1);
        DoubleBuffer mouseY = BufferUtils.createDoubleBuffer(1);
        GLContext.createFromCurrent();
        sight = new Sight();
        ArrayList<Block> blocks = new ArrayList<Block>();
        blocks.add(new Block(-0.5, 0.5, 0.2));
        blocks.add(new Block(0.5, -0.5, 0.2));
        double margin = 0.1;
        sight.loadMap(margin, blocks);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        while (glfwWindowShouldClose(window) == GL_FALSE) {
            glfwPollEvents();
            glfwGetCursorPos(window, mouseX, mouseY);
            double x = mouseX.get();
            double y = mouseY.get();
            if (x < 0 + 800*margin)
                x = 0 + 800*margin;
            if (x > 800 - 800*margin)
                x = 800 - 800*margin;
            if (y < 0 + 600*margin)
                y = 0 + 600*margin;
            if (y > 600 - 600*margin)
                y = 600 - 600*margin;
            sight.setLightLocation(2.0*x/800-1.0, -(2.0*y/600-1.0));
            mouseX.rewind();
            mouseY.rewind();
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            sight.render();
            glfwSwapBuffers(window);
        }
    }

    public static void main(String[] args) {
        new Main().run();
    }
}