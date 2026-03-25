package com.voxelgame.engine;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Compiles and links a GLSL vertex + fragment shader pair into an OpenGL shader program.
 * The compiled program lives on the GPU and must be explicitly cleaned up.
 */
public class ShaderProgram {

    /** GPU-side handle to the linked shader program. */
    private final int programId;

    /**
     * Creates, compiles, and links a shader program from two GLSL source files
     * on the classpath.
     *
     * @param vertexShaderPath   classpath path to the .vert file
     * @param fragmentShaderPath classpath path to the .frag file
     * @throws RuntimeException if compilation or linking fails
     */
    public ShaderProgram(String vertexShaderPath, String fragmentShaderPath) {
        // Compile each shader stage separately — they become GPU objects with integer IDs
        int vertexShaderId   = compileShader(vertexShaderPath,   GL20.GL_VERTEX_SHADER);
        int fragmentShaderId = compileShader(fragmentShaderPath, GL20.GL_FRAGMENT_SHADER);

        // Create the program object and attach both compiled shaders to it
        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vertexShaderId);
        GL20.glAttachShader(programId, fragmentShaderId);

        // Link — this connects the vertex shader outputs to the fragment shader inputs
        GL20.glLinkProgram(programId);
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == 0) {
            throw new RuntimeException("Shader linking failed:\n" + GL20.glGetProgramInfoLog(programId));
        }

        // Individual shader objects are no longer needed once linked into a program
        GL20.glDeleteShader(vertexShaderId);
        GL20.glDeleteShader(fragmentShaderId);
    }

    /**
     * Compiles a single shader stage from a classpath resource file.
     *
     * @param path      classpath path to the GLSL source file
     * @param shaderType GL20.GL_VERTEX_SHADER or GL20.GL_FRAGMENT_SHADER
     * @return the compiled shader's GPU handle
     */
    private int compileShader(String path, int shaderType) {
        String source = loadResource(path);

        int shaderId = GL20.glCreateShader(shaderType);
        GL20.glShaderSource(shaderId, source);
        GL20.glCompileShader(shaderId);

        // Check for compilation errors — GLSL errors appear here, like a compiler log
        if (GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) == 0) {
            throw new RuntimeException("Shader compilation failed [" + path + "]:\n"
                    + GL20.glGetShaderInfoLog(shaderId));
        }

        return shaderId;
    }

    /**
     * Reads a classpath resource file into a String.
     *
     * @param path classpath-relative path, e.g. "/shaders/default.vert"
     * @return file contents as a String
     */
    private String loadResource(String path) {
        try (InputStream in = ShaderProgram.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Shader resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader resource: " + path, e);
        }
    }

    /**
     * Sets a mat4 uniform variable in the shader program.
     * The program must be bound before calling this.
     *
     * @param name   the uniform variable name as declared in GLSL
     * @param matrix the JOML matrix to upload
     */
    public void setUniform(String name, Matrix4f matrix) {
        int location = GL20.glGetUniformLocation(programId, name);
        if (location == -1) {
            System.err.println("Warning: uniform '" + name + "' not found in shader program.");
            return;
        }
        // MemoryStack gives us a temporary off-heap buffer scoped to this try block.
        // It's fast and doesn't require manual free — the stack rewinds on close.
        try (MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(16);
            matrix.get(buffer);
            GL20.glUniformMatrix4fv(location, false, buffer);
        }
    }

    /**
     * Sets a float uniform variable in the shader program.
     * The program must be bound before calling this.
     *
     * @param name  the uniform variable name as declared in GLSL
     * @param value the float value to set
     */
    public void setUniform(String name, float value) {
        int location = GL20.glGetUniformLocation(programId, name);
        if (location == -1) {
            System.err.println("Warning: uniform '" + name + "' not found in shader program.");
            return;
        }
        GL20.glUniform1f(location, value);
    }

    /**
     * Sets an integer uniform (also used for sampler uniforms and booleans).
     * The program must be bound before calling this.
     */
    public void setUniform(String name, int value) {
        int location = GL20.glGetUniformLocation(programId, name);
        if (location == -1) {
            System.err.println("Warning: uniform '" + name + "' not found in shader program.");
            return;
        }
        GL20.glUniform1i(location, value);
    }

    /**
     * Sets a boolean uniform. Booleans are set as integers (1=true, 0=false) in GLSL.
     * The program must be bound before calling this.
     */
    public void setUniform(String name, boolean value) {
        setUniform(name, value ? 1 : 0);
    }

    /**
     * Binds this shader program — all subsequent draw calls will use it.
     */
    public void bind() {
        GL20.glUseProgram(programId);
    }

    /**
     * Unbinds any active shader program.
     */
    public void unbind() {
        GL20.glUseProgram(0);
    }

    /**
     * Deletes the shader program from GPU memory. Call on shutdown.
     */
    public void cleanup() {
        unbind();
        GL20.glDeleteProgram(programId);
    }
}