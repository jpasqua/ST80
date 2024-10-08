/*
Copyright (c) 2020, Dr. Hans-Walter Latz
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * The name of the author may not be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER "AS IS" AND ANY EXPRESS
OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package dev.hawala.st80vm;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;

import dev.hawala.st80vm.alto.AltoDisk;
import dev.hawala.st80vm.alto.AltoVmFilesHandler;
import dev.hawala.st80vm.alto.Disk.InvalidDiskException;
import dev.hawala.st80vm.d11xx.Dv6Specifics;
import dev.hawala.st80vm.d11xx.TajoDisk;
import dev.hawala.st80vm.d11xx.TajoVmFilesHandler;
import dev.hawala.st80vm.interpreter.Interpreter;
import dev.hawala.st80vm.interpreter.QuitSignal;
import dev.hawala.st80vm.memory.Memory;
import dev.hawala.st80vm.primitives.InputOutput;
import dev.hawala.st80vm.primitives.iVmFilesHandler;
import dev.hawala.st80vm.ui.DisplayBwPane;
import dev.hawala.st80vm.ui.KeyHandler;
import dev.hawala.st80vm.ui.MouseHandler;

/**
 * Main program for the ST80 emulator, which runs a Smalltalk virtual
 * machine implementing the specifications on the "Bluebook"
 * (Smalltalk-80 - The Language and its Implementation, by Adele Goldberg
 * and David Robson, 1983), allowing to work with a Smalltalk-80 V2 snapshot. 
 * 
 * @author Dr. Hans-Walter Latz / Berlin (2020)
 */
public class ST80 {
	
	/**
	 * Dummy file set handler for the fallback "image only" case
	 */
	private static class ImageOnlyVmFilesHandler implements iVmFilesHandler {
		
		public ImageOnlyVmFilesHandler(String fn) throws IOException {
			Memory.loadVirtualImage(fn);
		}

		@Override
		public void setSnapshotFilename(String filename) {
			// ignored
		}

		@Override
		public boolean saveSnapshot(PrintStream ps) {
			Memory.saveVirtualImage(null);
			return true;
		}

		@Override
		public boolean saveDiskChanges(PrintStream ps) {
			// no disk => no changes
			return true;
		}
	}
	
	/**
	 * Window state handler for preventing uncontrolled closing of the top-level window
	 */
	private static class WindowStateListener implements WindowListener {
		
		private final JFrame mainWindow;
		private final boolean statsAtEnd;
		
		public WindowStateListener(JFrame mainWindow, boolean statsAtEnd) {
			this.mainWindow = mainWindow;
			this.statsAtEnd = statsAtEnd;
		}

		@Override
		public void windowOpened(WindowEvent e) { }

		@Override
		public void windowClosing(WindowEvent e) {
			String[] messages = {
				"The Smalltalk-80 engine should be closed using the rootwindow context menu.",
				"Closing the main window will not snapshot the Smalltalk state, but disk changes can be saved.",
				"If the disk is not saved, all changes in this session will be lost.",
				"How do you want to proceed?"
			};
			String[] choices = { "Continue" , "Save disk and quit" , "Quit without saving" };
			int result = JOptionPane.showOptionDialog(
					this.mainWindow,
					messages,
					"Smalltalk-80 Engine is currently running",
					JOptionPane.YES_NO_CANCEL_OPTION,
					JOptionPane.QUESTION_MESSAGE,
				    null,
				    choices,
				    choices[0]);
			if (result == 1) {
				Interpreter.stopInterpreter();
			} else if (result == 2) {
				if (this.statsAtEnd) {
					System.out.printf("\n## terminating ST80, cause: close window (without saving disk)\n");
					Interpreter.printStats(System.out);
				}
				System.exit(0);
			}
		}

		@Override
		public void windowClosed(WindowEvent e) { }

		@Override
		public void windowIconified(WindowEvent e) { }

		@Override
		public void windowDeiconified(WindowEvent e) { }

		@Override
		public void windowActivated(WindowEvent e) { }

		@Override
		public void windowDeactivated(WindowEvent e) { }
	}
	
	/**
	 * 
	 * @param args
	 * @throws IOException
	 * @throws InvalidDiskException
	 */
	public static void main(String[] args) throws IOException, InvalidDiskException {
		/*
		 * get commandline arguments
		 */
		String imageFile = null;
		boolean haveStatusline = false;
		boolean doFullScreen = false;
		Integer timeAdjustMinutes = null;
		int tzOffsetMinutes = 60; // CET ~ Berlin
		int dstFirstDay = 31 + 28 + 31 - 6; // .................................... DST begins on sunday at or before 31.03.
		int dstLastDay = 31 + 28 + 31 + 30 + 31 + 30 + 31 + 31 + 30 + 31 - 6; // .. DST ends on sunday at or before 31.10. 
		boolean statsAtEnd = false;
		
		for (String arg : args) {
			String lcArg = arg.toLowerCase();
			if ("--statusline".equals(lcArg)) {
				haveStatusline = true;
			} else if (lcArg.startsWith("--timeadjust:")) {
				String minutes = lcArg.substring("--timeadjust:".length());
				try {
					timeAdjustMinutes = Integer.valueOf(minutes);
				} catch(NumberFormatException nfe) {
					System.out.printf("warning: ignoring invalid argument '%s' for --timeAdjust:\n", minutes);
				}
			} else if ("--stats".equals(lcArg)) {
				statsAtEnd = true;
			} else if ("--fullscreen".equals(lcArg)) {
				doFullScreen = true;
			} else if (lcArg.startsWith("--tz:")) {
				String[] parts = lcArg.substring(5).split(":");
				if (parts.length != 1 && parts.length != 3) {
					System.out.printf("warning: ignoring invalid option with argument count '%s'\n", arg);
				} else {
					boolean valid = true;
					int offset = tzOffsetMinutes;
					int firstDay = dstFirstDay;
					int lastDay = dstLastDay;
					try {
						offset = Integer.valueOf(parts[0]);
						valid &= offset >= -720 && offset <= 780;
					} catch(NumberFormatException nfe) {
						valid = false;
					}
					
					if (parts.length > 1) {
						try {
							firstDay = Integer.valueOf(parts[1]);
							lastDay = Integer.valueOf(parts[2]);
							valid &= firstDay >= 0 && firstDay <= 366;
							valid &= lastDay >= 0 && lastDay <= 366;
						} catch(NumberFormatException nfe) {
							valid = false;
						}
					}
					
					if (valid) {
						tzOffsetMinutes = offset;
						dstFirstDay = firstDay;
						dstLastDay = lastDay;
					} else {
						System.out.printf("warning: ignoring option with invalid values '%s'!\n", arg);
					}
				}
			} else if (arg.startsWith("--")) {
				System.out.printf("warning: ignoring invalid option '%s'\n", arg);
			} else if (imageFile == null) {
				imageFile = arg;
			} else {
				System.out.printf("warning: ignoring argument '%s'\n", arg);
			}
		}
		if (imageFile == null) {
			System.out.printf("error: missing image (base)filename, aborting\n");
			System.out.printf("\nUsage: st80 [--statusline] [--stats] [--timeadjust:nn] image-file[.im]\n");
			return;
		}
		if (doFullScreen) { haveStatusline = false; }
		
		/*
		 * create the file set handler for the given image file, loading the Smalltalk image and
		 * possibly the associated Alto disk, and register it to the relevant primitive implementations
		 */
		StringBuilder messages = new StringBuilder();
		iVmFilesHandler vmFiles = AltoVmFilesHandler.forImageFile(imageFile, messages);
		if (vmFiles == null) {
			vmFiles = TajoVmFilesHandler.forImageFile(imageFile, messages);
		}
		if (vmFiles == null) {
			System.out.println(messages.toString());
			try {
				vmFiles = new ImageOnlyVmFilesHandler(imageFile);
			} catch(FileNotFoundException e) {
				return; // a simple file not found has already been issued by the above handlers...
			}
		}
		InputOutput.setVmFilesHandler(vmFiles);
		AltoDisk.setVmFilesHandler(vmFiles);
		TajoDisk.setVmFilesHandler(vmFiles);
		
		/*
		 * setup Smalltalk time (for correcting the hard-coded Xerox-PARC timezone)
		 */
		if (timeAdjustMinutes != null) {
			InputOutput.setTimeAdjustmentMinutes(timeAdjustMinutes);
		}
		Dv6Specifics.setLocalTimeParameters(tzOffsetMinutes, dstFirstDay, dstLastDay);
		
		/*
		 * build a rather simple Java-Swing UI
		 */
		
		// create top-level window
		JFrame mainFrame = createMainFrame(doFullScreen, haveStatusline);
		mainFrame.addWindowListener(new WindowStateListener(mainFrame, statsAtEnd));

		EventQueue.invokeLater(() -> mainFrame.setVisible(true));
		
		/*
		 * restart the virtual-image, running the Smalltalk interpreter
		 * in the main thread until it finished by itself
		 */
		int suspendedContextAtSnapshot = Interpreter.firstContext();
		Interpreter.setVirtualImageRestartContext(suspendedContextAtSnapshot);
		try {
			Interpreter.interpret();
		} catch(QuitSignal qs) {
			vmFiles.saveDiskChanges(System.out);
			if (statsAtEnd) {
				System.out.printf("\n## terminating ST80, cause: %s\n", qs.getMessage());
				Interpreter.printStats(System.out);
			}
			mainFrame.setVisible(false);
			System.exit(0);
		} catch(Exception e) {
			vmFiles.saveDiskChanges(System.out);
			try { Thread.sleep(100); } catch (InterruptedException e1) { }
			e.printStackTrace();
			if (statsAtEnd) {
				try { Thread.sleep(100); } catch (InterruptedException e1) { }
				Interpreter.printStats(System.out);
			}
			mainFrame.setVisible(false);
			System.exit(0);
		}
	}

	private static JFrame createMainFrame(boolean attemptFullScreen, boolean showStats) {
		JFrame window = new JFrame();
		window.setTitle("Smalltalk-80 Engine");
		window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

		int spacing = 2;
		Dimension screenDims = new Dimension(640, 480);
			// The ultimate dims may be updated from the smalltalk environment ; e.g.:
		  // DisplayScreen displayExtent: 1024@768
		if (attemptFullScreen) {
			window.setUndecorated(true);
			window.setResizable(false);
			GraphicsEnvironment graphics = GraphicsEnvironment.getLocalGraphicsEnvironment();
			GraphicsDevice device = graphics.getDefaultScreenDevice();
			if (device.isFullScreenSupported()) {
				device.setFullScreenWindow(window);
				screenDims = new Dimension(device.getDisplayMode().getWidth(), device.getDisplayMode().getHeight());
				// We have to be careful here. The Smalltalk display is represent as an array object.
				// Since this is a 16 bit machine, the maximum size of the array is 65533 16-bit-words:
				//   (max(16-bit) minus 2 words for the length and class).
				// This limits the maximum display geometry: ((pixel-width + 15) / 16) * pixel-height < 65533
				// Ensure that we aren't exceeding that.
				if ( (((screenDims.width + 15) / 16) * screenDims.height) > 65533) {
					// Fallback to a known working size, the 1186 screen dims
					screenDims = new Dimension(1152, 862);
				}
				spacing = 0;
			}
		}

		window.getContentPane().setLayout(new BorderLayout(spacing, spacing));

		// the b/w display bitmap panel with mouse/keyboard handlers
		DisplayBwPane displayPanel = new DisplayBwPane(window, screenDims);
		window.getContentPane().add(displayPanel, BorderLayout.CENTER);
		MouseHandler mouseHandler = new MouseHandler(displayPanel);
		displayPanel.addMouseMotionListener(mouseHandler);
		displayPanel.addMouseListener(mouseHandler);
		displayPanel.addKeyListener(new KeyHandler());
		
		// connect the (Java) display panel with the (Smalltalk) display bitmap
		InputOutput.registerDisplayPane(displayPanel);
		
		// add the status line 
		if (showStats) {
			JLabel statusLine = new JLabel(" ST80 Engine not running");
			statusLine.setFont(new Font("Monospaced", Font.BOLD, 12));
			window.getContentPane().add(statusLine, BorderLayout.SOUTH);
			Interpreter.setStatusConsumer( s -> statusLine.setText(s) );
		}
		
		// finalize the top-level window and display it in an own thread
		window.pack();
		window.setResizable(false);

		return window;
	}
}
