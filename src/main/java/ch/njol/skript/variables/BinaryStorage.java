package ch.njol.skript.variables;

import ch.njol.skript.Skript;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.registrations.Classes;
import org.jetbrains.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class BinaryStorage extends VariablesStorage {

	private static final int HEADER_SIZE = 16; // Magic Num (4) + version (4) + index offset (8)
	private static final int MAGIC_NUMBER = 0x534B5242; //
	private static final int VERSION = 1;

	private RandomAccessFile raf;
	private FileChannel channel;

	public BinaryStorage(String type) {
		super(type);
	}

	@Override
	protected @Nullable String getValue(SectionNode sectionNode, String key) {
		return super.getValue(sectionNode, key);
	}

	@Override
	protected boolean load_i(SectionNode n) {
		try {
			raf = new RandomAccessFile(file, "rw");
			channel = raf.getChannel();

			if (raf.length() == 0) {
				writeHeader();
				return true;
			}

			ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
			channel.read(header, 0);
			header.flip();

			int magic = header.getInt();
			if (magic != MAGIC_NUMBER) {
				throw new IOException("Invalid file format. Expected magic number: " + MAGIC_NUMBER + ", but got: " + magic);
			}

			int version = header.getInt();
			if (version != VERSION) {
				throw new IOException("Unsupported version: " + version + ". Expected version: " + VERSION);
			}

			header.getLong(); // Skip indexOffset as it's not used

			// Read all variables from the file
			long currentPos = HEADER_SIZE;
			while (currentPos < channel.size()) {
				ByteBuffer buffer = ByteBuffer.allocate(2048);
				channel.read(buffer, currentPos);
				buffer.flip();

				// Break if we can't read a complete int
				if (buffer.remaining() < 4) {
					break;
				}

				String name = readString(buffer);
				String type = readString(buffer);

				// Read value length and value
				int valueLen = buffer.getInt();
				byte[] value = new byte[valueLen];
				buffer.get(value);

				// Update position for next iteration
				currentPos += buffer.position();

				// Deserialize and notify Variables system
				Object deserializedValue = Classes.deserialize(type, value);
				Variables.variableLoaded(name, deserializedValue, this);
			}

			return true;
		} catch (IOException e) {
			Skript.error("Failed to initialize binary storage: " + e.getMessage());
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}

	private void writeHeader() throws IOException {
		ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
		header.putInt(MAGIC_NUMBER);
		header.putInt(VERSION);
		header.putLong(HEADER_SIZE); // initial index offset
		header.flip();
		channel.write(header, 0);
	}

	@Override
	protected void allLoaded() {

	}

	@Override
	protected boolean requiresFile() {
		return true;
	}

	@Override
	protected File getFile(String fileName) {
		return new File(fileName + ".skrb");
	}

	@Override
	protected boolean connect() {
		try {
			if (raf == null) {
				raf = new RandomAccessFile(file, "rw");
				channel = raf.getChannel();
			}
			return true;
		} catch (IOException e) {
			Skript.error("Failed to connect to binary storage: " + e.getMessage());
			return false;
		}
	}

	@Override
	protected void disconnect() {
		try {
			channel.close();
			raf.close();
			channel = null;
			raf = null;
		} catch (IOException e) {
			Skript.error("Error disconnecting from binary storage: " + e.getMessage());
		}
	}

	@Override
	protected boolean save(String name, @Nullable String type, @Nullable byte[] value) {
		try {
			if (type == null && value == null) {
				return true;
			}

			byte[] nameBytes = name.getBytes();
			byte[] typeBytes = type.getBytes();

			ByteBuffer data = ByteBuffer.allocate(4 + nameBytes.length + 4 + typeBytes.length + 4 + value.length);
			data.putInt(nameBytes.length); // Write name length
			data.put(nameBytes); // Write name
			data.putInt(typeBytes.length); // Write type length
			data.put(typeBytes); // Write type (as a string)
			data.putInt(value.length); // Write value length
			data.put(value); // Write value
			data.flip();

			// Write to file
			long offset = raf.length();
			channel.write(data, offset);

			return true;
		} catch (IOException e) {
			Skript.error("Failed to save variable: " + e.getMessage());
			return false;
		}
	}

	private String readString(ByteBuffer buffer) {
		int length = buffer.getInt();
		byte[] bytes = new byte[length];
		buffer.get(bytes);
		return new String(bytes);
	}
}
