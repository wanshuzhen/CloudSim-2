/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2010, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.power;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.Map.Entry;

import javax.swing.plaf.basic.BasicInternalFrameTitlePane.MaximizeAction;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.lists.PowerVmList;

/**
 * PowerVmAllocationPolicySingleThreshold is an VMAllocationPolicy that
 * chooses a host with the least power increase due to utilization increase
 * caused by the VM allocation.
 *
 * @author		Anton Beloglazov
 * @since		CloudSim Toolkit 2.0
 */
public class PowerVmAllocationPolicySingleThreshold extends VmAllocationPolicySimple {

	/** The hosts PEs. */
	protected Map<String, Integer> hostsPes;

	/** The hosts utilization. */
	protected Map<Integer, Double> hostsUtilization;

	/** The hosts memory. */
	protected Map<Integer, Integer> hostsRam;

	/** The hosts bw. */
	protected Map<Integer, Long> hostsBw;

	/** The old allocation. */
	private List<Map<String, Object>> savedAllocation;

	/** The utilization threshold. */
	private double utilizationThreshold;
	

	
	private Boolean minimizeMigration = false;

	/**
	 * Instantiates a new vM provisioner mpp.
	 *
	 * @param list the list
	 * @param utilizationThreshold the utilization bound
	 */
	public PowerVmAllocationPolicySingleThreshold(List<? extends PowerHost> list, double utilizationThreshold) {
		super(list);
		setSavedAllocation(new ArrayList<Map<String,Object>>());
		setUtilizationThreshold(utilizationThreshold);		
	}

	/**
	 * Determines a host to allocate for the VM.
	 *
	 * @param vm the vm
	 *
	 * @return the host
	 */
	public PowerHost findHostForVm(Vm vm) {
		double minPower = Double.MAX_VALUE;
		PowerHost allocatedHost = null;

		for (PowerHost host : this.<PowerHost>getHostList()) {
			if (host.isSuitableForVm(vm)) {
				double maxUtilization = getMaxUtilizationAfterAllocation(host, vm);
				//if ((!vm.isRecentlyCreated() && maxUtilization > getUtilizationThreshold()) || (vm.isRecentlyCreated() && maxUtilization > 1.0)) {
				if ( maxUtilization > getUtilizationThreshold() ) {
					continue;
				}
				try {
					double powerAfterAllocation = getPowerAfterAllocation(host, vm);
					if (powerAfterAllocation != -1) {
						double powerDiff = powerAfterAllocation - host.getPower();
						if (powerDiff < minPower) {
							minPower = powerDiff;
							allocatedHost = host;
						}
					}
				} catch (Exception e) {
				}
			}
		}

		return allocatedHost;
	}

	/**
	 * Allocates a host for a given VM.
	 *
	 * @param vm VM specification
	 *
	 * @return $true if the host could be allocated; $false otherwise
	 *
	 * @pre $none
	 * @post $none
	 */
	@Override
	public boolean allocateHostForVm(Vm vm) {
		PowerHost allocatedHost = findHostForVm(vm);
		if (allocatedHost != null && allocatedHost.vmCreate(vm)) { //if vm has been succesfully created in the host
			getVmTable().put(vm.getUid(), allocatedHost);
			if (!Log.isDisabled()) {
				Log.printLine(String.format("%.2f: VM #" + vm.getId() + " has been allocated to the host #" + allocatedHost.getId() + "\n", CloudSim.clock()));
			}
			return true;
		}
		return false;
	}

	/**
	 * Releases the host used by a VM.
	 *
	 * @param vm the vm
	 *
	 * @pre $none
	 * @post none
	 */
	@Override
	public void deallocateHostForVm(Vm vm) {
		if (getVmTable().containsKey(vm.getUid())) {
			PowerHost host = (PowerHost) getVmTable().remove(vm.getUid());
			if (host != null) {
				host.vmDestroy(vm);
			}
		}
	}
	
	public void reMapping(List<? extends Vm> vmList, List<? extends PowerHost> hostList){
		double[] vCPU = new double[vmList.size()];
		double[] vMEM = {}; 
		double[] pCPU = new double[hostList.size()];
		double[] pMEM = {};
		double[] ePM = new double[hostList.size()];
		
		//order the VM list
		List<Vm> sortedVmList =  sortVM(vmList);
		Map vmMap = new HashMap<String, Vm>();
		for (int i = 0 ;i< sortedVmList.size();i++){
			vmMap.put(String.format("%d", i), sortedVmList.get(i));
			vCPU[i] = sortedVmList.get(i).getCurrentRequestedTotalMips();
		}
		//order the PM list
		List<PowerHost> sortedPmList =  sortPM(hostList);
		Map pmMap = new HashMap<String, PowerHost>();
		for (int i = 0 ;i< sortedPmList.size();i++){	
			pmMap.put(String.format("%d", i), sortedPmList.get(i));
			pCPU[i] = sortedPmList.get(i).getTotalMips();
			ePM[i] = sortedPmList.get(i).getMaxPower();
		}
	}
	
	private  List<Vm> sortVM(List<? extends Vm> vmList) {
		List<Vm> nList = new ArrayList<Vm>();
		
		for (int i=0;i<vmList.size();i++){
			double requestedTotalMipsMax = -1;
			int vmInx = -1;
			for (int j = 0;j<vmList.size();j++){
				if ( nList.contains( vmList.get(j)) ) continue;
				if(vmList.get(j).getCurrentRequestedTotalMips() > requestedTotalMipsMax){					
					vmInx = j;
					requestedTotalMipsMax = vmList.get(j).getCurrentRequestedTotalMips();
				}
			}
			if (vmInx > -1){
				nList.add(vmList.get(vmInx));
			}
		}		
		
		return nList;
	}
	
	private  List<PowerHost> sortPM(List<? extends PowerHost>hostList) {
		List<PowerHost> nList = new ArrayList<PowerHost>();
		
		for (int i=0;i<hostList.size();i++){
			double totalMipsMax = -1;
			int hostInx = -1;
			for (int j = 0;j<hostList.size();j++){
				if ( nList.contains( hostList.get(j)) ) continue;
				if(hostList.get(j).getTotalMips() > totalMipsMax){					
					hostInx = j;
					totalMipsMax = hostList.get(j).getTotalMips();
				}
			}
			if (hostInx > -1){
				nList.add(hostList.get(hostInx));
			}
		}		
		
		return nList;
	}

	/**
	 * Optimize allocation of the VMs according to current utilization.
	 *
	 * @param vmList the vm list
	 * @param utilizationThreshold the utilization bound
	 *
	 * @return the array list< hash map< string, object>>
	 */
	@Override
	public List<Map<String, Object>> optimizeAllocation(List<? extends Vm> vmList) {
		List<Map<String, Object>> migrationMap = new ArrayList<Map<String, Object>>();
		if (vmList.isEmpty()) {
			return migrationMap;
		}
		saveAllocation(vmList);
		List<Vm> vmsToRestore = new ArrayList<Vm>();
		vmsToRestore.addAll(vmList);

		Map<Integer,Host> inMigrationHosts = new HashMap<Integer, Host>();
		List<Vm> vmsToMigrate = new ArrayList<Vm>();
		for (Vm vm : vmList) {
			if ( vm.isInMigration()){
				inMigrationHosts.put(getVmTable().get(vm.getUid()).getId(), getVmTable().get(vm.getUid()));
				continue;
			}
			if ( inMigrationHosts.containsKey(  vm.getHost().getId()) ){				
				continue;
			}
			if (vm.isRecentlyCreated() ) {				
				continue;
			}
			vmsToMigrate.add(vm);
			inMigrationHosts.put(vm.getHost().getId(),vm.getHost());
			vm.getHost().vmDestroy(vm);			
		}
		PowerVmList.sortByCpuUtilization(vmsToMigrate);

		for (PowerHost host : this.<PowerHost>getHostList()) {
			host.reallocateMigratingVms();
		}

		for (Vm vm : vmsToMigrate) {
			PowerHost oldHost = (PowerHost) getVmTable().get(vm.getUid());
			PowerHost allocatedHost = findHostForVm(vm);
			if (allocatedHost != null){
				allocatedHost.vmCreate(vm);
				Log.printLine("VM #" + vm.getId() + " allocated to host #" + allocatedHost.getId());
				if( allocatedHost.getId() != oldHost.getId()) {				
					Map<String, Object> migrate = new HashMap<String, Object>();
					migrate.put("vm", vm);
					migrate.put("host", allocatedHost);
					migrationMap.add(migrate);
				}
			}
		}

		restoreAllocation(vmsToRestore, getHostList());

		return migrationMap;
	}


		
	/**
	 * Save allocation.
	 *
	 * @param vmList the vm list
	 */
	protected void saveAllocation(List<? extends Vm> vmList) {
		getSavedAllocation().clear();
		for (Vm vm : vmList) {
			Map<String, Object> map = new HashMap<String, Object>();
			map.put("vm", vm);
			map.put("host", vm.getHost());
			getSavedAllocation().add(map);
			
			double utilization = vm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
			double utilizationMem = vm.getCloudletScheduler().getTotalUtilizationOfRam(CloudSim.clock());
			Log.printLineToVmFile((int)CloudSim.clock(), vm.getId(), vm.getHost().getId(), 
					utilization, vm.getMips()*utilization, 
					vm.getHost().getMaxAvailableMips(),
					vm.getHost().getVmScheduler().getUsedMips(),
					vm.getHost().getRam(),
					vm.getCurrentRequestedRam()
					);
			
		}
	}

	/**
	 * Restore allocation.
	 *
	 * @param vmsToRestore the vms to restore
	 */
	protected void restoreAllocation(List<Vm> vmsToRestore, List<Host> hostList) {
		for (Host host : hostList) {
			host.vmDestroyAll();
			host.reallocateMigratingVms();
		}
		for (Map<String, Object> map : getSavedAllocation()) {
			Vm vm = (Vm) map.get("vm");
			PowerHost host = (PowerHost) map.get("host");
			if (!vmsToRestore.contains(vm)) {
				continue;
			}
			if (!host.vmCreate(vm)) {
				Log.printLine("Something is wrong, the VM can's be restored");
				System.exit(0);
			}
			getVmTable().put(vm.getUid(), host);
			Log.printLine("Restored VM #" + vm.getId() + " on host #" + host.getId());
		}
	}

	/**
	 * Gets the power after allocation.
	 *
	 * @param host the host
	 * @param vm the vm
	 *
	 * @return the power after allocation
	 */
	protected double getPowerAfterAllocation(PowerHost host, Vm vm) {
		List<Double> allocatedMipsForVm = null;
		PowerHost allocatedHost = (PowerHost) vm.getHost();

		if (allocatedHost != null) {
			allocatedMipsForVm = allocatedHost.getAllocatedMipsForVm(vm);
		}

		if (!host.allocatePesForVm(vm, vm.getCurrentRequestedMips())) {
			return -1;
		}

		double power = host.getPower();

		host.deallocatePesForVm(vm);

		if (allocatedHost != null && allocatedMipsForVm != null) {
			vm.getHost().allocatePesForVm(vm, allocatedMipsForVm);
		}

		return power;
	}

	/**
	 * Gets the power after allocation.
	 *
	 * @param host the host
	 * @param vm the vm
	 *
	 * @return the power after allocation
	 */
	protected double getMaxUtilizationAfterAllocation(PowerHost host, Vm vm) {
		List<Double> allocatedMipsForVm = null;
		PowerHost allocatedHost = (PowerHost) vm.getHost();

		if (allocatedHost != null) {
			allocatedMipsForVm = vm.getHost().getAllocatedMipsForVm(vm);
		}

		if (!host.allocatePesForVm(vm, vm.getCurrentRequestedMips())) {
			return Double.MAX_VALUE;
		}

		double maxUtilization = host.getMaxUtilizationAmongVmsPes(vm);

		host.deallocatePesForVm(vm);

		if (allocatedHost != null && allocatedMipsForVm != null) {
			vm.getHost().allocatePesForVm(vm, allocatedMipsForVm);
		}

		return maxUtilization;
	}

	/**
	 * Gets the saved allocation.
	 *
	 * @return the saved allocation
	 */
	protected List<Map<String, Object>> getSavedAllocation() {
		return savedAllocation;
	}

	/**
	 * Sets the saved allocation.
	 *
	 * @param savedAllocation the saved allocation
	 */
	protected void setSavedAllocation(List<Map<String, Object>> savedAllocation) {
		this.savedAllocation = savedAllocation;
	}

	/**
	 * Gets the utilization bound.
	 *
	 * @return the utilization bound
	 */
	protected double getUtilizationThreshold() {
		return utilizationThreshold;
	}

	/**
	 * Sets the utilization bound.
	 *
	 * @param utilizationThreshold the new utilization bound
	 */
	protected void setUtilizationThreshold(double utilizationThreshold) {
		this.utilizationThreshold = utilizationThreshold;
	}
	

	public Boolean getMinimizeMigration() {
		return minimizeMigration;
	}

	public void setMinimizeMigration(Boolean minimizeMigration) {
		this.minimizeMigration = minimizeMigration;
	}
	
	public String getPolicyDesc() {
		String rst = String.format("ST%.2f", getUtilizationThreshold());
		return rst;
	}
}
