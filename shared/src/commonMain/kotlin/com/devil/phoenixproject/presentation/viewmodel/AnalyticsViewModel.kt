package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.VolumeDataPoint
import com.devil.phoenixproject.domain.model.VolumePeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for analytics screen.
 * Handles volume data loading and period selection for analytics charts.
 */
class AnalyticsViewModel(
    private val workoutRepository: WorkoutRepository
) : ViewModel() {

    // Volume data state
    private val _volumeData = MutableStateFlow<List<VolumeDataPoint>>(emptyList())
    val volumeData: StateFlow<List<VolumeDataPoint>> = _volumeData.asStateFlow()

    // Selected time period for volume chart
    private val _selectedVolumePeriod = MutableStateFlow(VolumePeriod.MONTHLY)
    val selectedVolumePeriod: StateFlow<VolumePeriod> = _selectedVolumePeriod.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadVolumeData()
    }

    /**
     * Change the selected volume period and reload data.
     */
    fun setVolumePeriod(period: VolumePeriod) {
        _selectedVolumePeriod.value = period
        loadVolumeData()
    }

    /**
     * Load volume data based on the currently selected period.
     */
    private fun loadVolumeData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val data = when (_selectedVolumePeriod.value) {
                    VolumePeriod.WEEKLY -> workoutRepository.getWeeklyVolume(12)
                    VolumePeriod.MONTHLY -> workoutRepository.getMonthlyVolume(12)
                    VolumePeriod.YEARLY -> workoutRepository.getYearlyVolume(5)
                }
                _volumeData.value = data
            } catch (e: Exception) {
                // Handle error - keep existing data or clear
                _volumeData.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Refresh volume data (e.g., after completing a workout)
     */
    fun refreshVolumeData() {
        loadVolumeData()
    }
}
