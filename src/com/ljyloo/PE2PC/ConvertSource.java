package com.ljyloo.PE2PC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import com.mojang.nbt.CompoundTag;
import com.mojang.nbt.ListTag;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.OldDataLayer;

class ConvertSource {
	
	//Data2D
	private final static int HEIGHTMAP_BYTES = 512;
	private final static int BIOMEDATA_BYTES = 256;
	//SubChunk
	private final static int DATALAYER_BITS = 4;
	private final static int BLOCKDATA_BYTES = 4096;
	private final static int METADATA_BYTES = 2048;
	private final static int SKYLIGHTDATA_BYTES = 2048;
	private final static int BLOCKLIGHTDATA_BYTES = 2048;
	
	private Chunk current;
	private HashMap<String, Chunk> comChunks = new HashMap<>();
	
	public Chunk createChunkIfNotExists(int xPos, int zPos){
		String comKey = xPos+","+zPos;
		if (!comChunks.containsKey(comKey)){
			//System.out.println("New comChunks");
			Chunk chunk = this.new Chunk(xPos, zPos);
			
			comChunks.put(comKey, chunk);
			return chunk;
		}else{
			return comChunks.get(comKey);
		}
	}
	
	public CompoundTag createSectionIfNotExists(int chunkY){
		if(current == null)
			throw new NullPointerException("Set current chunk first.");
		else if(chunkY < 0){
			throw new IllegalArgumentException("chunkY < 0");
		}
		
		CompoundTag section;
		@SuppressWarnings("unchecked")
		ListTag<CompoundTag> sections = (ListTag<CompoundTag>)current.level.getList("Sections");
		
		for(int i=0;i<sections.size();i++){
			byte y = sections.get(i).getByte("Y");
			if(chunkY == y){
				section = sections.get(i);
				return section;
			}
		}
		
		//Create new section
		section = new CompoundTag();
		section.putByte("Y", (byte)(chunkY & 0xFF));
		
		sections.add(section);
		
		return section;
	}
	
	public void setCurrent(int xPos, int zPos){
		this.current = createChunkIfNotExists(xPos, zPos);
	}
	
	/*
	 * Convert HeightMap and Biomes
	 * 
	 * Not sure every biome are shared by PC and PE.
	 * So I just store them directly.
	 */
	public void convertData2D(byte[] value ){
		if(current == null)
			throw new NullPointerException("Set current chunk first.");
		
		//Convert HeightMap
		byte[] heightData = new byte[HEIGHTMAP_BYTES];
		
		int offset = 0;
		System.arraycopy(value, offset, heightData, 0, heightData.length);
		offset += heightData.length;
		
		// byte array to int array
		int[] height = new int[256];
		
		int map = HEIGHTMAP_BYTES/(height.length*2);
		int limit = heightData.length/2;
		for(int i=0;i<limit;i++){
			//(i+1)*2-1 = 2*i+1 (use 2*i , 2*i+1)
			height[i*map] = heightData[2*i+1]<<8 | heightData[2*i];
		}
		
		current.level.putIntArray("HeightMap",height);
		
		//Convert Biomes
		byte[] biomes = new byte[BIOMEDATA_BYTES];
		
		System.arraycopy(value, offset, biomes, 0, biomes.length);
		offset += biomes.length;
		
		current.level.putByteArray("Biomes", biomes);
		
		/*
		//Check if something was left
		if(value.length > offset){
			int left = value.length - offset;
			int[] pos = getCurrent();
			System.out.printf("Chunk at %d, %d \nThere's some data left behind Biomes, length: %d \n",
					pos[0], pos[1], left);
		}
		*/
	}
	
	/*
	 * Convert everything about subChunk but block light and sky light
	 */
	public void convertSubChunk(int chunkY , byte[] value){
		if(current == null)
			throw new NullPointerException("Set current chunk first.");
		
		CompoundTag section = createSectionIfNotExists(chunkY);
		
		//BlockID, BlockData
		byte[] oldBlock = new byte[BLOCKDATA_BYTES];
		OldDataLayer oldMeta = new OldDataLayer(METADATA_BYTES << 1, DATALAYER_BITS);
		
		//Get data
		int offset = 1;
		System.arraycopy(value, offset, oldBlock, 0, oldBlock.length);
		offset += oldBlock.length;
		System.arraycopy(value, offset, oldMeta.data, 0, oldMeta.data.length);
		offset += oldMeta.data.length;
		
		//Converted array and DataLayers
		byte[] block = new byte[BLOCKDATA_BYTES];
		
		DataLayer meta = new DataLayer(METADATA_BYTES << 1, DATALAYER_BITS);
		
		//XZY -> YZX
		for(byte x=0;x<16;x++){
			for(byte y=0;y<16;y++){
				for(byte z=0;z<16;z++){
					byte[] converted = 
							UnsharedData.blockFilter(oldBlock[ (x << 8) | ( z << 4) | y], (byte)(oldMeta.get(x, y, z) & 0xff));

					block[(y << 8) | (z << 4) | x] = converted[0];
					meta.set(x, y, z, converted[1]);
				}
			}
		}
		
		//Light data 
		// will be recalculated by Minecraft.
		byte[] skyLight = new byte[SKYLIGHTDATA_BYTES];
		Arrays.fill(skyLight, (byte)0);
		byte[] blockLight = new byte[BLOCKLIGHTDATA_BYTES];
		Arrays.fill(blockLight, (byte)0);
		
		//Put tag
		section.putByteArray("Blocks", block);
		section.putByteArray("Data", meta.data);
		section.putByteArray("SkyLight", skyLight);
		section.putByteArray("BlockLight", blockLight);
		
		@SuppressWarnings("unchecked")
		ListTag<CompoundTag> sections = (ListTag<CompoundTag>)(current.level.getList("Sections"));
		sections.add(section);
		
		current.legacy = false;
	}
	
	public void convertLegacyTerrain(byte[] value){
		if(current == null)
			throw new NullPointerException("Set current chunk first.");
		
		if(!current.legacy) return;
		
		for(int chunkY = 0;chunkY < 8;chunkY++){
			
			//find non-air
			boolean allAir = true;
			for (int i=0;i<BLOCKDATA_BYTES;i++) {
				int base = chunkY*BLOCKDATA_BYTES;
				if(value[base+i] != 0){
					allAir = false;
					break;
				}
			}
			
			if(allAir) continue;
			
			int size = BLOCKDATA_BYTES + METADATA_BYTES + 1;
			byte[] subChunk = new byte[size];
			
			//Block Data
			int offset = chunkY*BLOCKDATA_BYTES;
			System.arraycopy(value, offset, subChunk, 1, BLOCKDATA_BYTES);
			
			//Meta Data
			offset = 8*BLOCKDATA_BYTES+chunkY*METADATA_BYTES;
			System.arraycopy(value, offset, subChunk, BLOCKDATA_BYTES + 1, METADATA_BYTES);
			
			convertSubChunk(chunkY, subChunk);
		}
	}
	
	public void convertData2DLegacy(byte[] value){}
	
	public void convertBlockEntity(byte[] value){}
	
	public void convertEntity(byte[] value){}
	
	public void convertSonw(byte[] value){}
	
	public int[] getCurrent(){
		return new int[]{current.level.getInt("xPos"), current.level.getInt("zPos")};
	}
	
	public List<CompoundTag> getConvertedChunks(){
		List<CompoundTag> com = new ArrayList<>();
		comChunks.values().iterator().forEachRemaining(x -> com.add(x.root));
		return com;
	}
	
	class Chunk{
		
		boolean legacy = true;
		
		CompoundTag root;
		CompoundTag level;
		
		public Chunk(int chunkX, int chunkZ){
			root = new CompoundTag();
			level = new CompoundTag();
			
			root.put("Level", level);
			level.putByte("LightPopulated", (byte)0);
			level.putByte("TerrainPopulated", (byte)1);
			level.putByte("V", (byte)1);
			level.putInt("xPos", chunkX);
			level.putInt("zPos", chunkZ);
			level.putLong("InhabitedTime", 0);
			level.putLong("LastUpdate", 0);
			
			ListTag<CompoundTag> sectionTags = new ListTag<CompoundTag>("Sections");
			level.put("Sections", sectionTags);
			
			/*
			level.putByteArray("Biomes", biomes);
			
			level.put("Entities", new ListTag<CompoundTag>("Entities"));
			
			level.put("TileEntities", new ListTag<CompoundTag>("TileEntities"));
			*/	
		}
	}
}
